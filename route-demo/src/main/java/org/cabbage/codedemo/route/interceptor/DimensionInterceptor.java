package org.cabbage.codedemo.route.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.route.config.DimensionConfig;
import org.cabbage.codedemo.route.config.DimensionManager;
import org.cabbage.codedemo.route.context.DataSourceContext;
import org.cabbage.codedemo.route.context.DimensionContext;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 维度拦截器：从 URL 路径提取维度 key，将 tableName 写入 DimensionContext
 * URL 约定：/{module}/{domain}/... → dimensionKey = {module}_{domain}
 * 示例：/beta/normal/distribution → dimensionKey = beta_normal
 *
 * 同时写入 DataSourceContext（一期值为 null 无影响，二期分库时自动生效）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DimensionInterceptor implements HandlerInterceptor {

    private final DimensionManager dimensionManager;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String dimensionKey = extractDimensionKey(req.getRequestURI());
        DimensionConfig config = dimensionManager.getConfig(dimensionKey);

        DimensionContext.set(config.getDimensionKey(), config.getTableName());

        // Phase 2：分库时 dataSource 非空，自动切换数据源；一期留空不影响
        if (config.getDataSource() != null) {
            DataSourceContext.set(config.getDataSource());
        }

        log.debug("维度路由: key={}, table={}, dataSource={}",
                config.getDimensionKey(), config.getTableName(), config.getDataSource());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        DimensionContext.clear();
        DataSourceContext.clear();
    }

    /**
     * 从 URI 提取维度 key
     * /beta/normal/distribution → parts[1]="beta", parts[2]="normal" → "beta_normal"
     */
    private String extractDimensionKey(String uri) {
        String[] parts = uri.split("/");
        if (parts.length >= 3) {
            return parts[1] + "_" + parts[2];
        }
        log.warn("无法从 URI 提取维度 key: {}，使用默认维度", uri);
        return dimensionManager.getDefaultKey();
    }
}
