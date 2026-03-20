package org.cabbage.codedemo.faultdatasync.service;

import java.time.LocalDate;

/**
 * 同步任务状态管理接口
 */
public interface SyncTaskRecordService {

    /**
     * 创建或更新同步记录为 RUNNING 状态（每次 sync 开始前调用）
     */
    void createOrUpdateRunning(String domain, LocalDate dataDate);

    /**
     * 标记为 MESSAGES_SENT：所有 MQ 消息已发送完毕
     *
     * @param batchCount 本次发送的 MQ 批次总数
     */
    void updateMessagesSent(String domain, LocalDate dataDate, int batchCount);

    /**
     * 标记为 FAILED，记录错误信息
     */
    void updateFailed(String domain, LocalDate dataDate, String errorMessage);

    /**
     * MQ 消费端调用：已完成批次数 +1，若达到 batchCount 则自动置为 SUCCESS
     */
    void incrementCompletedBatch(String domain, LocalDate dataDate);

    /**
     * Producer 补偿检查：updateMessagesSent 之后调用，
     * 应对所有批次均早于 updateMessagesSent 消费完成的极端场景。
     */
    void checkAndMarkSuccessIfAllDone(String domain, LocalDate dataDate);
}
