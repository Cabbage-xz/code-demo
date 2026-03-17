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
 * 同步批次状态实体（对应 sync_batch_record 表）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sync_batch_record")
public class SyncBatchRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String domain;

    private LocalDate dataDate;

    /** 批次序号（从 0 开始） */
    private Integer batchIndex;

    /** 本批 pull 的起始 rank（游标值） */
    private Long startRank;

    /** 本批最大 rank，pull 成功后回填 */
    private Long endRank;

    /** 本批记录数 */
    private Integer recordCount;

    /** PENDING/SUCCESS/FAILED */
    private String pullStatus;

    /** PENDING/SUCCESS/FAILED */
    private String insertStatus;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
