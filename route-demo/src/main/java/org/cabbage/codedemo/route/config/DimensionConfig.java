package org.cabbage.codedemo.route.config;

import lombok.Data;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 单个维度的路由配置
 * Phase 2 启用 dataSource 字段，一期留空即可
 */
@Data
public class DimensionConfig {

    /** 维度 key，由 DimensionManager 从 Map 的 key 回填，无需在 yml 中声明 */
    private String dimensionKey;

    /** 对应的物理表名，如 beta_normal_fault */
    private String tableName;

    /** 描述，仅用于日志/文档 */
    private String description;

    /**
     * 数据源 key，对应 DynamicRoutingDataSource 中注册的 key（beta / comm）
     * 一期单库不填，二期分库时填写
     */
    private String dataSource;
}
