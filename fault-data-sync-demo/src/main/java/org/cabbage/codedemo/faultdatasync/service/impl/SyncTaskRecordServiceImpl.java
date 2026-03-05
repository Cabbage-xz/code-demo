package org.cabbage.codedemo.faultdatasync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.faultdatasync.entity.SyncTaskRecordEntity;
import org.cabbage.codedemo.faultdatasync.enums.SyncStatus;
import org.cabbage.codedemo.faultdatasync.mapper.SyncTaskRecordMapper;
import org.cabbage.codedemo.faultdatasync.service.SyncTaskRecordService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskRecordServiceImpl implements SyncTaskRecordService {

    private final SyncTaskRecordMapper syncTaskRecordMapper;

    @Override
    public void createOrUpdateRunning(String domain, LocalDate dataDate) {
        SyncTaskRecordEntity existing = findByDomainAndDate(domain, dataDate);
        if (existing == null) {
            SyncTaskRecordEntity record = SyncTaskRecordEntity.builder()
                    .domain(domain)
                    .dataDate(dataDate)
                    .status(SyncStatus.RUNNING.name())
                    .batchCount(0)
                    .completedBatchCount(0)
                    .totalRecords(0)
                    .retryCount(0)
                    .startTime(LocalDateTime.now())
                    .build();
            syncTaskRecordMapper.insert(record);
        } else {
            syncTaskRecordMapper.update(null, new LambdaUpdateWrapper<SyncTaskRecordEntity>()
                    .eq(SyncTaskRecordEntity::getDomain, domain)
                    .eq(SyncTaskRecordEntity::getDataDate, dataDate)
                    .set(SyncTaskRecordEntity::getStatus, SyncStatus.RUNNING.name())
                    .set(SyncTaskRecordEntity::getBatchCount, 0)
                    .set(SyncTaskRecordEntity::getCompletedBatchCount, 0)
                    .set(SyncTaskRecordEntity::getTotalRecords, 0)
                    .set(SyncTaskRecordEntity::getErrorMessage, null)
                    .set(SyncTaskRecordEntity::getStartTime, LocalDateTime.now())
                    .set(SyncTaskRecordEntity::getEndTime, null)
                    .setSql("retry_count = retry_count + 1"));
        }
        log.info("[SyncTask] domain={} date={} → RUNNING", domain, dataDate);
    }

    @Override
    public void updateMessagesSent(String domain, LocalDate dataDate, int totalRecords, int batchCount) {
        syncTaskRecordMapper.update(null, new LambdaUpdateWrapper<SyncTaskRecordEntity>()
                .eq(SyncTaskRecordEntity::getDomain, domain)
                .eq(SyncTaskRecordEntity::getDataDate, dataDate)
                .set(SyncTaskRecordEntity::getStatus, SyncStatus.MESSAGES_SENT.name())
                .set(SyncTaskRecordEntity::getTotalRecords, totalRecords)
                .set(SyncTaskRecordEntity::getBatchCount, batchCount));
        log.info("[SyncTask] domain={} date={} → MESSAGES_SENT, batches={}, records={}",
                domain, dataDate, batchCount, totalRecords);
    }

    @Override
    public void updateFailed(String domain, LocalDate dataDate, String errorMessage) {
        String truncated = errorMessage != null && errorMessage.length() > 500
                ? errorMessage.substring(0, 500) : errorMessage;
        syncTaskRecordMapper.update(null, new LambdaUpdateWrapper<SyncTaskRecordEntity>()
                .eq(SyncTaskRecordEntity::getDomain, domain)
                .eq(SyncTaskRecordEntity::getDataDate, dataDate)
                .set(SyncTaskRecordEntity::getStatus, SyncStatus.FAILED.name())
                .set(SyncTaskRecordEntity::getErrorMessage, truncated)
                .set(SyncTaskRecordEntity::getEndTime, LocalDateTime.now()));
        log.error("[SyncTask] domain={} date={} → FAILED: {}", domain, dataDate, errorMessage);
    }

    @Override
    public void incrementCompletedBatch(String domain, LocalDate dataDate) {
        // SQL 直接引用 DB 列 batch_count，无需额外 SELECT
        int affected = syncTaskRecordMapper.incrementCompletedBatch(domain, dataDate);
        if (affected == 0) {
            log.warn("[SyncTask] incrementCompletedBatch: UPDATE 无影响 (记录不存在或已非活跃状态) domain={} date={}",
                    domain, dataDate);
        }
    }

    @Override
    public void checkAndMarkSuccessIfAllDone(String domain, LocalDate dataDate) {
        int affected = syncTaskRecordMapper.checkAndMarkSuccessIfAllDone(domain, dataDate);
        if (affected > 0) {
            log.info("[SyncTask] checkAndMarkSuccessIfAllDone: 所有批次已提前消费完成，补触发 SUCCESS domain={} date={}",
                    domain, dataDate);
        }
    }

    private SyncTaskRecordEntity findByDomainAndDate(String domain, LocalDate dataDate) {
        return syncTaskRecordMapper.selectOne(new LambdaQueryWrapper<SyncTaskRecordEntity>()
                .eq(SyncTaskRecordEntity::getDomain, domain)
                .eq(SyncTaskRecordEntity::getDataDate, dataDate));
    }
}
