package org.cabbage.codedemo.faultdatasync.service;

import java.time.LocalDate;

/**
 * 单个 domain+date 的故障数据同步接口
 */
public interface FaultSyncService {

    /**
     * 同步指定领域、指定日期的故障数据：
     * <ol>
     *   <li>更新 sync_task_record 为 RUNNING</li>
     *   <li>全量删除 fault_record 中该 domain+date 的旧数据</li>
     *   <li>循环拉取数据源（rank 游标翻页），每批发送到 MQ</li>
     *   <li>更新 sync_task_record 为 MESSAGES_SENT</li>
     * </ol>
     */
    void syncDomainDate(String domain, LocalDate date);
}
