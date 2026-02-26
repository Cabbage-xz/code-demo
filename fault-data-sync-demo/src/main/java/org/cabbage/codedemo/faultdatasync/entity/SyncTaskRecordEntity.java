package org.cabbage.codedemo.faultdatasync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 同步任务状态实体（对应 sync_task_record 表）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sync_task_record")
public class SyncTaskRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 领域 */
    private String domain;

    /** 同步数据日期 */
    private LocalDate dataDate;

    /** 状态：PENDING/RUNNING/MESSAGES_SENT/SUCCESS/FAILED */
    private String status;

    /** 已发送 MQ 批次总数 */
    private Integer batchCount;

    /** 已成功插入 DB 的批次数 */
    private Integer completedBatchCount;

    /** 本次同步总记录数 */
    private Integer totalRecords;

    /** 重试次数 */
    private Integer retryCount;

    /** 失败原因 */
    private String errorMessage;

    /** 同步开始时间 */
    private LocalDateTime startTime;

    /** 同步结束时间 */
    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
