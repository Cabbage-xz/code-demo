package org.cabbage.codedemo.faultdatasync.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.faultdatasync.client.FaultDataSourceClient;
import org.cabbage.codedemo.faultdatasync.entity.SyncBatchRecordEntity;
import org.cabbage.codedemo.faultdatasync.mapper.FaultRecordMapper;
import org.cabbage.codedemo.faultdatasync.model.FaultRecordDTO;
import org.cabbage.codedemo.faultdatasync.mq.producer.FaultDataProducer;
import org.cabbage.codedemo.faultdatasync.service.FaultSyncService;
import org.cabbage.codedemo.faultdatasync.service.SyncBatchRecordService;
import org.cabbage.codedemo.faultdatasync.service.SyncTaskRecordService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 单个 domain+date 故障数据同步核心实现
 * <p>
 * 首次运行：DELETE 全量 + 循环 pull 全部批次，每批记录到 sync_batch_record。
 * 重试运行：不 DELETE，查询失败批次 → 仅从其 startRank 重新拉取，INSERT IGNORE 保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaultSyncServiceImpl implements FaultSyncService {

    private final FaultDataSourceClient sourceClient;
    private final FaultDataProducer faultDataProducer;
    private final FaultRecordMapper faultRecordMapper;
    private final SyncTaskRecordService syncTaskRecordService;
    private final SyncBatchRecordService syncBatchRecordService;

    @Value("${fault-sync.page-size:5000}")
    private int pageSize;

    @Override
    public void syncDomainDate(String domain, LocalDate date) {
        log.info("[Sync] 开始同步 domain={} date={}", domain, date);
        syncTaskRecordService.createOrUpdateRunning(domain, date);

        boolean isRetry = syncBatchRecordService.hasSuccessBatch(domain, date);
        if (isRetry) {
            log.info("[Sync] domain={} date={} 检测到历史成功批次，走重试路径", domain, date);
            runRetrySync(domain, date);
        } else {
            runFirstSync(domain, date);
        }
    }

    /**
     * 首次运行：DELETE 全量 + 拉取全部批次
     */
    private void runFirstSync(String domain, LocalDate date) {
        int deleted = faultRecordMapper.deleteByDomainAndDate(domain, date);
        log.info("[Sync] domain={} date={} 删除旧记录 {} 条", domain, date, deleted);

        long lastRank = 0;
        int batchIndex = 0;

        try {
            while (true) {
                long startRank = lastRank;
                List<FaultRecordDTO> records;
                try {
                    records = sourceClient.pull(domain, date, lastRank, pageSize);
                } catch (Exception e) {
                    syncBatchRecordService.markPullFailed(domain, date, batchIndex, startRank, e.getMessage());
                    throw e;
                }

                if (records.isEmpty()) {
                    log.info("[Sync] domain={} date={} 数据源返回空，拉取结束", domain, date);
                    break;
                }

                long endRank = records.stream().mapToLong(FaultRecordDTO::getRank).max().orElse(lastRank);
                syncBatchRecordService.markPullSuccess(domain, date, batchIndex, startRank, endRank, records.size());
                faultDataProducer.sendBatch(domain, date, batchIndex, startRank, records);
                lastRank = endRank;
                batchIndex++;

                log.debug("[Sync] domain={} date={} 已发送第 {} 批，本批 {} 条",
                        domain, date, batchIndex, records.size());

                if (records.size() < pageSize) {
                    log.info("[Sync] domain={} date={} 最后一批（size={} < pageSize={}），拉取结束",
                            domain, date, records.size(), pageSize);
                    break;
                }
            }

            syncTaskRecordService.updateMessagesSent(domain, date, batchIndex);
            log.info("[Sync] domain={} date={} 首次同步完成，共 {} 批", domain, date, batchIndex);
            syncTaskRecordService.checkAndMarkSuccessIfAllDone(domain, date);

        } catch (Exception e) {
            syncTaskRecordService.updateFailed(domain, date, e.getMessage());
            throw new RuntimeException(
                    String.format("Sync failed for domain=%s date=%s", domain, date), e);
        }
    }

    /**
     * 重试运行：不 DELETE，仅重跑失败批次。
     * <ul>
     *   <li>pull_status=FAILED：从 startRank 继续拉到末尾（后续批次均未运行）</li>
     *   <li>insert_status=FAILED：从 startRank 重拉该批次（单次 pull）</li>
     * </ul>
     * INSERT IGNORE 保证已写入数据不被重复插入。
     */
    private void runRetrySync(String domain, LocalDate date) {
        List<SyncBatchRecordEntity> failedBatches = syncBatchRecordService.findFailed(domain, date);
        if (failedBatches.isEmpty()) {
            log.info("[Sync] domain={} date={} 无失败批次，直接补检查", domain, date);
            syncTaskRecordService.checkAndMarkSuccessIfAllDone(domain, date);
            return;
        }

        int sentBatchCount = 0;

        try {
            for (SyncBatchRecordEntity batch : failedBatches) {
                if ("FAILED".equals(batch.getPullStatus())) {
                    // pull 失败：rank 游标断开，从 startRank 拉到末尾（含后续所有批次）
                    long lastRank = batch.getStartRank();
                    int batchIndex = batch.getBatchIndex();

                    while (true) {
                        long startRank = lastRank;
                        List<FaultRecordDTO> records;
                        try {
                            records = sourceClient.pull(domain, date, lastRank, pageSize);
                        } catch (Exception e) {
                            syncBatchRecordService.markPullFailed(domain, date, batchIndex, startRank, e.getMessage());
                            throw e;
                        }

                        if (records.isEmpty()) break;

                        long endRank = records.stream().mapToLong(FaultRecordDTO::getRank).max().orElse(lastRank);
                        syncBatchRecordService.markPullSuccess(domain, date, batchIndex, startRank, endRank, records.size());
                        faultDataProducer.sendBatch(domain, date, batchIndex, startRank, records);
                        lastRank = endRank;
                        batchIndex++;
                        sentBatchCount++;

                        if (records.size() < pageSize) break;
                    }
                } else {
                    // insert 失败：重拉该批次（单次 pull，INSERT IGNORE 幂等写入）
                    long startRank = batch.getStartRank();
                    List<FaultRecordDTO> records;
                    try {
                        records = sourceClient.pull(domain, date, startRank, pageSize);
                    } catch (Exception e) {
                        syncBatchRecordService.markPullFailed(domain, date, batch.getBatchIndex(), startRank, e.getMessage());
                        throw e;
                    }

                    if (!records.isEmpty()) {
                        long endRank = records.stream().mapToLong(FaultRecordDTO::getRank).max().orElse(startRank);
                        syncBatchRecordService.markPullSuccess(domain, date, batch.getBatchIndex(), startRank, endRank, records.size());
                        faultDataProducer.sendBatch(domain, date, batch.getBatchIndex(), startRank, records);
                        sentBatchCount++;
                    }
                }
            }

            syncTaskRecordService.updateMessagesSent(domain, date, sentBatchCount);
            log.info("[Sync] domain={} date={} 重试同步完成，重发 {} 批", domain, date, sentBatchCount);
            syncTaskRecordService.checkAndMarkSuccessIfAllDone(domain, date);

        } catch (Exception e) {
            syncTaskRecordService.updateFailed(domain, date, e.getMessage());
            throw new RuntimeException(
                    String.format("Retry sync failed for domain=%s date=%s", domain, date), e);
        }
    }
}
