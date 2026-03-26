package org.cabbage.codedemo.faultdatasync.service;

import org.cabbage.codedemo.faultdatasync.entity.SyncBatchRecordEntity;

import java.time.LocalDate;
import java.util.List;

/**
 * 批次级状态管理服务
 * <p>
 * 通过 sync_batch_record 表记录每批的 startRank、pull_status、insert_status，
 * 支撑重试时精确定位并重跑失败批次，避免全量删除重拉。
 */
public interface SyncBatchRecordService {

    /** pull 成功：upsert 批次记录，回填 end_rank 和 record_count */
    void markPullSuccess(String domain, LocalDate dataDate, int batchIndex,
                         long startRank, long endRank, int recordCount);

    /** pull 失败：写入或更新批次记录，pull_status=FAILED */
    void markPullFailed(String domain, LocalDate dataDate, int batchIndex,
                        long startRank, String errorMessage);

    /**
     * 消费入库成功：幂等地将 insert_status 置为 SUCCESS。
     * <p>
     * 内部使用 {@code WHERE insert_status != 'SUCCESS'} 条件更新，
     * 返回受影响行数：1 表示首次成功，0 表示该批次已处理过（重复消费）。
     * 调用方应根据返回值决定是否继续调用 incrementCompletedBatch，避免计数虚高。
     *
     * @return 受影响行数（0 = 重复消费，无需再推进完成计数）
     */
    int markInsertSuccess(String domain, LocalDate dataDate, int batchIndex);

    /** 消费入库失败（进 DLQ）：insert_status=FAILED */
    void markInsertFailed(String domain, LocalDate dataDate, int batchIndex, String errorMessage);

    /**
     * 判断当前 domain+date 是否存在 pull_status=SUCCESS 的批次。
     * 用于区分首次运行（false）与重试运行（true）。
     */
    boolean hasSuccessBatch(String domain, LocalDate dataDate);

    /** 查询所有失败批次（pull 或 insert 失败），按 batchIndex 升序 */
    List<SyncBatchRecordEntity> findFailed(String domain, LocalDate dataDate);
}
