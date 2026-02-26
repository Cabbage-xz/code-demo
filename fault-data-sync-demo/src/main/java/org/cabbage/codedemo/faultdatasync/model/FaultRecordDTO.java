package org.cabbage.codedemo.faultdatasync.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 原数据源返回的故障记录 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaultRecordDTO {

    /** 数据领域 */
    private String domain;

    /** 数据所属日期 */
    private LocalDate dataDate;

    /** 当天唯一排序值（游标翻页依据） */
    private long rank;

    /** 故障类型 */
    private String faultType;

    /** 设备 ID */
    private String deviceId;

    /** 故障详情 */
    private String faultDetail;
}
