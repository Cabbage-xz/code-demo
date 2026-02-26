package org.cabbage.codedemo.faultdatasync.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * MQ 消息体：每批 5000 条故障数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaultDataBatchMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 数据领域 */
    private String domain;

    /** 数据所属日期 */
    private LocalDate dataDate;

    /** 批次序号（从 0 开始） */
    private int batchIndex;

    /** 本批次故障记录列表（最多 5000 条） */
    private List<FaultRecordDTO> records;
}
