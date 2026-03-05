package org.cabbage.codedemo.route.config;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 维度配置管理器：加载 yml 配置，提供维度查询能力
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DimensionManager {

    private final DimensionProperties props;

    @PostConstruct
    public void init() {
        // 将 Map key 回填到 DimensionConfig.dimensionKey，方便日志和调试
        props.getMappings().forEach((key, config) -> config.setDimensionKey(key));
        log.info("维度配置加载完成，共 {} 个维度，默认维度: {}",
                props.getMappings().size(), props.getDefaultKey());
        props.getMappings().forEach((key, config) ->
                log.debug("  {} -> table={}, dataSource={}", key, config.getTableName(), config.getDataSource()));
    }

    /**
     * 根据维度 key 获取配置，不存在时降级到默认维度
     */
    public DimensionConfig getConfig(String dimensionKey) {
        if (StrUtil.isBlank(dimensionKey)) {
            return getDefaultConfig();
        }
        DimensionConfig config = props.getMappings().get(dimensionKey);
        if (config == null) {
            log.warn("未找到维度配置: {}，降级到默认维度: {}", dimensionKey, props.getDefaultKey());
            return getDefaultConfig();
        }
        return config;
    }

    public boolean hasDimension(String dimensionKey) {
        return StrUtil.isNotBlank(dimensionKey) && props.getMappings().containsKey(dimensionKey);
    }

    public String getDefaultKey() {
        return props.getDefaultKey();
    }

    private DimensionConfig getDefaultConfig() {
        DimensionConfig config = props.getMappings().get(props.getDefaultKey());
        if (config == null) {
            throw new IllegalStateException("默认维度未配置: " + props.getDefaultKey());
        }
        return config;
    }
}
