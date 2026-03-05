package org.cabbage.codedemo.route.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 从 application.yml 的 dimension 节点加载维度配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "dimension")
public class DimensionProperties {

    /** 默认维度 key，路由提取失败时使用 */
    private String defaultKey = "beta_normal";

    /** 维度配置表：key = dimensionKey，value = DimensionConfig */
    private Map<String, DimensionConfig> mappings = new HashMap<>();
}
