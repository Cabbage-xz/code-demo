package org.cabbage.codedemo.route.interceptor;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.route.config.RouteConfig;
import org.cabbage.codedemo.route.context.RouteContext;
import org.cabbage.codedemo.route.manager.RouteConfigManager;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 路由拦截器
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RouteInterceptor implements HandlerInterceptor {

    private final RouteConfigManager routeConfigManager;

    @SneakyThrows
    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        log.debug("路由拦截器，开始处理请求：{}", req.getRequestURI());

        try {
            String routeKey = extractRouteKey(req);
            if (StrUtil.isBlank(routeKey)) {
                log.warn("无法提取路由key，使用默认路由");
                routeKey = "default";
            }
            log.info("提取到路由Key：{}", routeKey);

            RouteConfig config = routeConfigManager.getRouteConfig(routeKey);
            RouteContext.setRouteInfo(routeKey, config.getSuffix(), config.getModule());
            log.info("路由信息已设置");
            return true;
        } catch (Exception e) {
            log.error("路由拦截器处理异常", e);
            RouteContext.setRouteInfo("default", "/betaSuffix", "faultDetailMapper");
            return true;
        }
    }

    private String extractRouteKey(HttpServletRequest request) {
        String routeKey = extractFromPath(request);
        if (routeKey != null) {
            log.debug("从URL路径提取路由Key：{}", routeKey);
            return routeKey;
        }
        return null;
    }

    /**
     * 从url 路径解析
     * @param request 请求
     * @return 路由key
     */
    private String extractFromPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String[] parts = uri.split("/");

        // 遍历路径段，查找已知的路由key
        for (String part : parts) {
            if (routeConfigManager.hasRoute(part)) {
                return part;
            }
        }
        return null;
    }

    /**
     * 请求完成后清理上下文
     * @param req 请求
     * @param resp 响应
     * @param handler handler
     * @param ex ex
     */
    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        log.debug("路由拦截器，清理上下文");
        RouteContext.clear();
    }
}
