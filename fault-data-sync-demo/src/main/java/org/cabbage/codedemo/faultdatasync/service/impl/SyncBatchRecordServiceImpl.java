package org.cabbage.codedemo.faultdatasync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.faultdatasync.entity.SyncBatchRecordEntity;
import org.cabbage.codedemo.faultdatasync.mapper.SyncBatchRecordMapper;
import org.cabbage.codedemo.faultdatasync.service.SyncBatchRecordService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncBatchRecordServiceImpl implements SyncBatchRecordService {

    private final SyncBatchRecordMapper syncBatchRecordMapper;

    @Override
    public void markPullSuccess(String domain, LocalDate dataDate, int batchIndex,
                                long startRank, long endRank, int recordCount) {
        syncBatchRecordMapper.upsertPullSuccess(domain, dataDate, batchIndex, startRank, endRank, recordCount);
        log.debug("[BatchRecord] pull SUCCESS domain={} date={} batch={} startRank={} endRank={} count={}",
                domain, dataDate, batchIndex, startRank, endRank, recordCount);
    }

    @Override
    public void markPullFailed(String domain, LocalDate dataDate, int batchIndex,
                               long startRank, String errorMessage) {
        String truncated = truncate(errorMessage);
        SyncBatchRecordEntity existing = findByKey(domain, dataDate, batchIndex);
        if (existing == null) {
            syncBatchRecordMapper.insert(SyncBatchRecordEntity.builder()
                    .domain(domain)
                    .dataDate(dataDate)
                    .batchIndex(batchIndex)
                    .startRank(startRank)
                    .endRank(0L)
                    .recordCount(0)
                    .pullStatus("FAILED")
                    .insertStatus("PENDING")
                    .errorMessage(truncated)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build());
        } else {
            syncBatchRecordMapper.update(null, new LambdaUpdateWrapper<SyncBatchRecordEntity>()
                    .eq(SyncBatchRecordEntity::getDomain, domain)
                    .eq(SyncBatchRecordEntity::getDataDate, dataDate)
                    .eq(SyncBatchRecordEntity::getBatchIndex, batchIndex)
                    .set(SyncBatchRecordEntity::getPullStatus, "FAILED")
                    .set(SyncBatchRecordEntity::getErrorMessage, truncated)
                    .set(SyncBatchRecordEntity::getUpdateTime, LocalDateTime.now()));
        }
        log.warn("[BatchRecord] pull FAILED domain={} date={} batch={} startRank={}",
                domain, dataDate, batchIndex, startRank);
    }

    @Override
    public void markInsertSuccess(String domain, LocalDate dataDate, int batchIndex) {
        syncBatchRecordMapper.update(null, new LambdaUpdateWrapper<SyncBatchRecordEntity>()
                .eq(SyncBatchRecordEntity::getDomain, domain)
                .eq(SyncBatchRecordEntity::getDataDate, dataDate)
                .eq(SyncBatchRecordEntity::getBatchIndex, batchIndex)
                .set(SyncBatchRecordEntity::getInsertStatus, "SUCCESS")
                .set(SyncBatchRecordEntity::getUpdateTime, LocalDateTime.now()));
        log.debug("[BatchRecord] insert SUCCESS domain={} date={} batch={}", domain, dataDate, batchIndex);
    }

    @Override
    public void markInsertFailed(String domain, LocalDate dataDate, int batchIndex, String errorMessage) {
        syncBatchRecordMapper.update(null, new LambdaUpdateWrapper<SyncBatchRecordEntity>()
                .eq(SyncBatchRecordEntity::getDomain, domain)
                .eq(SyncBatchRecordEntity::getDataDate, dataDate)
                .eq(SyncBatchRecordEntity::getBatchIndex, batchIndex)
                .set(SyncBatchRecordEntity::getInsertStatus, "FAILED")
                .set(SyncBatchRecordEntity::getErrorMessage, truncate(errorMessage))
                .set(SyncBatchRecordEntity::getUpdateTime, LocalDateTime.now()));
        log.warn("[BatchRecord] insert FAILED domain={} date={} batch={}", domain, dataDate, batchIndex);
    }

    @Override
    public boolean hasSuccessBatch(String domain, LocalDate dataDate) {
        return syncBatchRecordMapper.selectCount(new LambdaQueryWrapper<SyncBatchRecordEntity>()
                .eq(SyncBatchRecordEntity::getDomain, domain)
                .eq(SyncBatchRecordEntity::getDataDate, dataDate)
                .eq(SyncBatchRecordEntity::getPullStatus, "SUCCESS")) > 0;
    }

    @Override
    public List<SyncBatchRecordEntity> findFailed(String domain, LocalDate dataDate) {
        return syncBatchRecordMapper.findFailed(domain, dataDate);
    }

    private SyncBatchRecordEntity findByKey(String domain, LocalDate dataDate, int batchIndex) {
        return syncBatchRecordMapper.selectOne(new LambdaQueryWrapper<SyncBatchRecordEntity>()
                .eq(SyncBatchRecordEntity::getDomain, domain)
                .eq(SyncBatchRecordEntity::getDataDate, dataDate)
                .eq(SyncBatchRecordEntity::getBatchIndex, batchIndex));
    }

    private String truncate(String s) {
        return s != null && s.length() > 500 ? s.substring(0, 500) : s;
    }
}
