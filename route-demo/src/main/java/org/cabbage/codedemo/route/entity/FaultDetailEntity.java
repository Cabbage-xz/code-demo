package org.cabbage.codedemo.route.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 故障详情统一实体
 * 六张表结构完全一致，@TableName 中的 "fault_detail" 作为占位符，
 * 由 DynamicTableNameInnerInterceptor 在运行时替换为真实表名
 */
@Data
@TableName("fault_detail")
public class FaultDetailEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
}
