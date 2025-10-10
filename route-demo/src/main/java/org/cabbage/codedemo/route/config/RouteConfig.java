package org.cabbage.codedemo.route.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 路由配置
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RouteConfig {
    /**
     * 模块名称，对应 @RouteMapper 的 module
     */
    private String module;

    /**
     * 路由后缀，对应 @RouteMapper 的 value
     */
    private String suffix;

    /**
     * 路由描述
     */
    private String description;

    /**
     * 是否启用（可选）
     */
    private Boolean enabled;

    /**
     * 基础版本构造函数
     * @param module 模块
     * @param suffix 后缀
     * @param description 描述
     */
    public RouteConfig(String module, String suffix, String description) {
        this.module = module;
        this.suffix = suffix;
        this.description = description;
        this.enabled = true;
    }

    /**
     * 判断是否启用
     */
    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    /**
     * 获取完整的路由标识（用于日志）
     */
    public String getRouteIdentifier() {
        return String.format("%s:%s", module, suffix);
    }

    @Override
    public String toString() {
        return String.format("RouteConfig[module=%s, suffix=%s, enabled=%s, desc=%s]",
                module, suffix, enabled, description);
    }

}
