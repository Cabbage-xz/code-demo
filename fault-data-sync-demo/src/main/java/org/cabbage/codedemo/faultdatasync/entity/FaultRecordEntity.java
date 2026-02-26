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
 * 故障记录实体（对应 fault_record 表）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fault_record")
public class FaultRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据领域 */
    private String domain;

    /** 数据所属日期 */
    private LocalDate dataDate;

    /** 当天唯一排序值 */
    private Integer rank;

    /** 故障类型 */
    private String faultType;

    /** 设备 ID */
    private String deviceId;

    /** 故障详情 */
    private String faultDetail;

    /** 入库时间 */
    private LocalDateTime createTime;
}
