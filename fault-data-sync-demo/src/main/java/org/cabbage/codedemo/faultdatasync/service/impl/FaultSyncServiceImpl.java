package org.cabbage.codedemo.faultdatasync.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.faultdatasync.client.FaultDataSourceClient;
import org.cabbage.codedemo.faultdatasync.mapper.FaultRecordMapper;
import org.cabbage.codedemo.faultdatasync.model.FaultRecordDTO;
import org.cabbage.codedemo.faultdatasync.mq.producer.FaultDataProducer;
import org.cabbage.codedemo.faultdatasync.service.FaultSyncService;
import org.cabbage.codedemo.faultdatasync.service.SyncTaskRecordService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 单个 domain+date 故障数据同步核心实现
 * <p>
 * 流程：
 * <ol>
 *   <li>sync_task_record → RUNNING</li>
 *   <li>DELETE fault_record WHERE domain=? AND data_date=?（全量覆盖策略）</li>
 *   <li>循环 pull（rank 游标翻页） → 每批发 MQ</li>
 *   <li>sync_task_record → MESSAGES_SENT</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaultSyncServiceImpl implements FaultSyncService {

    private final FaultDataSourceClient sourceClient;
    private final FaultDataProducer faultDataProducer;
    private final FaultRecordMapper faultRecordMapper;
    private final SyncTaskRecordService syncTaskRecordService;

    @Value("${fault-sync.page-size:5000}")
    private int pageSize;

    @Override
    public void syncDomainDate(String domain, LocalDate date) {
        log.info("[Sync] 开始同步 domain={} date={}", domain, date);

        // Step 1: 更新任务状态为 RUNNING
        syncTaskRecordService.createOrUpdateRunning(domain, date);

        // Step 2: 全量覆盖 - 先删除旧数据（允许中间短暂空窗）
        int deleted = faultRecordMapper.deleteByDomainAndDate(domain, date);
        log.info("[Sync] domain={} date={} 删除旧记录 {} 条", domain, date, deleted);

        // Step 3: 循环拉取并发送 MQ
        long lastRank = 0;
        int batchIndex = 0;
        int totalRecords = 0;

        try {
            while (true) {
                List<FaultRecordDTO> records = sourceClient.pull(domain, date, lastRank, pageSize);
                if (records.isEmpty()) {
                    log.info("[Sync] domain={} date={} 数据源返回空，拉取结束", domain, date);
                    break;
                }

                faultDataProducer.sendBatch(domain, date, batchIndex, records);
                totalRecords += records.size();
                lastRank = records.stream().mapToLong(FaultRecordDTO::getRank).max().orElse(lastRank);
                batchIndex++;

                log.debug("[Sync] domain={} date={} 已发送第 {} 批，本批 {} 条，累计 {} 条",
                        domain, date, batchIndex, records.size(), totalRecords);

                // 终止条件：返回数量 < pageSize，说明已是最后一批
                if (records.size() < pageSize) {
                    log.info("[Sync] domain={} date={} 最后一批（size={} < pageSize={}），拉取结束",
                            domain, date, records.size(), pageSize);
                    break;
                }
            }

            // Step 4: 更新任务状态为 MESSAGES_SENT
            syncTaskRecordService.updateMessagesSent(domain, date, totalRecords, batchIndex);
            log.info("[Sync] domain={} date={} 同步完成，共 {} 批，{} 条记录",
                    domain, date, batchIndex, totalRecords);

        } catch (Exception e) {
            syncTaskRecordService.updateFailed(domain, date, e.getMessage());
            throw new RuntimeException(
                    String.format("Sync failed for domain=%s date=%s", domain, date), e);
        }
    }
}
