package org.cabbage.codedemo.route.context;

import lombok.extern.slf4j.Slf4j;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 存储路由上下文
 */
@Slf4j
public class RouteContext {

    /**
     * 存储路由key 例如：beta_fault comm_fault
     */
    private static final ThreadLocal<String> ROUTE_KEY = new ThreadLocal<>();

    /**
     * 存储路由后缀 例如：/betaSuffix /commSuffix
     */
    private static final ThreadLocal<String> ROUTE_SUFFIX = new ThreadLocal<>();

    /**
     * 存储路由模块 例如：betaDetailMapper
     */
    private static final ThreadLocal<String> MODULE_NAME = new ThreadLocal<>();

    /**
     * 设置路由key
     * @param routeKey 路由key
     */
    public static void setRouteKey(String routeKey) {
        ROUTE_KEY.set(routeKey);
    }

    /**
     * 获取路由key
     * @return 路由key
     */
    public static String getRouteKey() {
        return ROUTE_KEY.get();
    }

    /**
     * 设置路由后缀
     * @param routeSuffix 路由后缀
     */
    public static void setRouteSuffix(String routeSuffix) {
        ROUTE_SUFFIX.set(routeSuffix);
    }

    /**
     * 获取路由后缀
     * @return 路由后缀
     */
    public static String getRouteSuffix() {
        return ROUTE_SUFFIX.get();
    }

    /**
     * 设置模块名称
     * @param moduleName 模块名称
     */
    public static void setModuleName(String moduleName) {
        MODULE_NAME.set(moduleName);
    }

    /**
     * 获取模块名称
     * @return 模块名称
     */
    public static String getModuleName() {
        return MODULE_NAME.get();
    }

    /**
     * 设置完整的路由信息
     */
    public static void setRouteInfo(String routeKey, String routeSuffix, String moduleName) {
        setRouteKey(routeKey);
        setRouteSuffix(routeSuffix);
        setModuleName(moduleName);
        log.debug("设置完整路由信息: routeKey={}, suffix={}, module={}",
                routeKey, routeSuffix, moduleName);
    }

    /**
     * 清除所有路由信息（必须在请求结束时调用）
     */
    public static void clear() {
        ROUTE_KEY.remove();
        ROUTE_SUFFIX.remove();
        MODULE_NAME.remove();
        log.debug("清除路由上下文");
    }

    /**
     * 获取当前上下文的所有信息（用于调试）
     */
    public static String getContextInfo() {
        return String.format("RouteContext[routeKey=%s, suffix=%s, module=%s]",
                getRouteKey(), getRouteSuffix(), getModuleName());
    }

}
