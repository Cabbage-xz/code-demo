package org.cabbage.codedemo.route.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 路由属性配置类
 */
@Component
@ConfigurationProperties(prefix = "route")
@Data
public class RouteConfigProperties {

    /**
     * 默认路由key
     */
    private String defaultKey = "default";

    /**
     * 严格模式
     */
    private boolean strictMode = false;

    /**
     * 路由映射配置
     * key：routeKey
     * value：RouteConfig
     */
    private Map<String, RouteConfig> mappings = new HashMap<>();
}
