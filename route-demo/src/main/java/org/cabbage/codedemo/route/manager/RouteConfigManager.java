package org.cabbage.codedemo.route.manager;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.route.config.RouteConfig;
import org.cabbage.codedemo.route.config.RouteConfigProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


/**
 * @author xzcabbage
 * @since 2025/10/10
 * 路由配置管理器
 * 处理前端与后端service mapper的映射关系
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RouteConfigManager {

    private final RouteConfigProperties routeConfigProperties;

    /**
     * 路由配置映射表
     * key：前端传来的路由标识
     * value：路由配置
     */
    private final Map<String, RouteConfig> routeConfigMap = new ConcurrentHashMap<>();

    @Value("${route.default-key:default}")
    private String defaultRouteKey;

    /**
     * 是否启用严格模式（路由不存在时抛异常，否则使用默认路由）
     */
    @Value("${route.strict-mode:false}")
    private boolean strictMode;

    /**
     * 初始化路由配置
     * 在Bean创建后自动执行
     */
    @PostConstruct
    public void initRouteConfig() {
        log.info("============== init route config ==============");

        // 清空现有配置
        routeConfigMap.clear();

        // 注册路由配置
        if (routeConfigProperties != null && CollUtil.isNotEmpty(routeConfigProperties.getMappings())) {
            registerConfigByFileRoutes();
        }

        // 验证配置
        validateConfig();

        log.info("==================== 路由配置初始化完成 ====================");
        log.info("总共注册 {} 个路由", routeConfigMap.size());
        log.info("默认路由: {}", defaultRouteKey);
        log.info("严格模式: {}", strictMode);

    }

    /**
     * 注册所有路由配置
     * 配置文件加载
     * 其他方式：硬编码或者读数据库
     */
    private void registerConfigByFileRoutes() {
        log.info("===registerConfigByFileRoutes===");
        Map<String, RouteConfig> mappings = routeConfigProperties.getMappings();

        mappings.forEach((key, value) -> {
            routeConfigMap.put(key, value);
            log.debug("从配置文件注册路由： routeKey={}, value={}", key, value);
        });

        log.info("配置文件路由注册完成，共{}个", routeConfigMap.size());

    }

    /**
     * 获取路由配置
     * @param routeKey 路由key
     * @return 路由配置
     */
    public RouteConfig getRouteConfig(String routeKey) {
        if (StrUtil.isBlank(routeKey)) {
            log.warn("路由key为空，使用默认路由");
            return getDefaultRoute();
        }

        RouteConfig config = routeConfigMap.get(routeKey);
        if (config == null) {
            log.warn("路由配置不存在：routeKey={}", routeKey);
            if (!strictMode) {
                // 未启用严格模式
                log.warn("使用默认路由：{}", defaultRouteKey);
                config = routeConfigMap.get(defaultRouteKey);
                if (config == null) {
                    // 抛出异常
                    throw new RuntimeException("未配置默认路由：" + defaultRouteKey);
                }
            }
        }
        // 若未启用
        if (!Objects.requireNonNull(config).getEnabled()) {
            log.warn("路由已禁用：{}", routeKey);

            if (!strictMode) {
                return getDefaultRoute();
            }
        }
        return config;
    }

    /**
     * 获取默认路由配置
     * @return 默认路由配置
     */
    public RouteConfig getDefaultRoute() {
        RouteConfig config = routeConfigMap.get(defaultRouteKey);
        if (config == null) {
            throw new RuntimeException("未配置默认路由：" + defaultRouteKey);
        }
        return config;
    }

    /**
     * 检查路由是否存在
     * @param routeKey 路由key
     * @return true-存在 false-不存在
     */
    public boolean hasRoute(String routeKey) {
        if (StrUtil.isBlank(routeKey)) {
            return false;
        }
        return routeConfigMap.containsKey(routeKey) && routeConfigMap.get(routeKey).isEnabled();
    }

    /**
     * 获取所有路由配置
     *
     * @return 所有路由配置的副本
     */
    public Map<String, RouteConfig> getAllRouteConfigs() {
        return new HashMap<>(routeConfigMap);
    }

    /**
     * 获取所有已启用的路由配置
     */
    public Map<String, RouteConfig> getEnabledRouteConfigs() {
        return routeConfigMap.entrySet().stream()
                .filter(entry -> entry.getValue().isEnabled())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }


    /**
     * 清空所有路由配置
     */
    public void clearAllRoutes() {
        routeConfigMap.clear();
        log.warn("已清空所有路由配置");
    }

    /**
     * 验证配置的完整性
     */
    private void validateConfig() {
        log.info("验证路由配置...");

        // 检查是否有路由配置
        if (routeConfigMap.isEmpty()) {
            log.error("警告: 没有注册任何路由配置！");
            return;
        }

        // 检查默认路由是否存在
        if (!routeConfigMap.containsKey(defaultRouteKey)) {
            log.error("警告: 默认路由不存在: {}", defaultRouteKey);
        }

        // 检查是否有重复的 module+suffix 组合
        Map<String, List<String>> duplicateCheck = new HashMap<>();
        routeConfigMap.forEach((routeKey, config) -> {
            String key = config.getRouteIdentifier();
            duplicateCheck.computeIfAbsent(key, k -> new ArrayList<>()).add(routeKey);
        });

        duplicateCheck.forEach((identifier, routeKeys) -> {
            if (routeKeys.size() > 1) {
                log.warn("发现重复的路由配置 {}: {}", identifier, routeKeys);
            }
        });

        log.info("路由配置验证完成");
    }
}
