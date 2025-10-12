package org.cabbage.codedemo.route.factory;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.route.annotation.RouteCustom;
import org.cabbage.codedemo.route.config.RouteConfig;
import org.cabbage.codedemo.route.context.RouteContext;
import org.cabbage.codedemo.route.manager.RouteConfigManager;
import org.cabbage.codedemo.route.mapper.BaseFaultDetailRouterMapper;
import org.cabbage.codedemo.route.service.IBaseFaultDetailService;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 统一bean路由工厂 管理带有@RouteMapper注解的Bean
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BeanRouteFactory implements ApplicationContextAware {
    private RouteConfigManager routeConfigManager;

    /**
     * Mapper路由映射表
     * 第一层key: module (如: faultDetail)
     * 第二层key: suffix (如: /betaSuffix)
     * value: Mapper实例
     *
     * 示例结构:
     * {
     *   "faultDetail": {
     *     "/betaSuffix": BetaFaultDetailMapper实例,
     *     "/commSuffix": CommFaultDetailMapper实例
     *   },
     *   "otherAppFaultDetail": {
     *     "/betaSuffix": BetaOtherAppFaultDetailMapper实例,
     *     "/commSuffix": CommOtherAppFaultDetailMapper实例
     *   }
     * }
     */
    private final Map<String, ConcurrentHashMap<String, Object>> mapperRouteMap = new ConcurrentHashMap<>();

    /**
     * Service路由映射表
     * 第一层key: module (如: faultDetail)
     * 第二层key: suffix (如: /betaSuffix)
     * value: Service实例
     *
     * 示例结构:
     * {
     *   "faultDetailService": {
     *     "/betaSuffix": BetaFaultDetailServiceImpl实例,
     *     "/commSuffix": CommFaultDetailServiceImpl实例
     *   },
     *   "otherAppFaultDetailService": {
     *     "/betaSuffix": BetaOtherAppFaultDetailServiceImpl实例,
     *     "/commSuffix": CommOtherAppFaultDetailServiceImpl实例
     *   }
     * }
     */
    private final Map<String, ConcurrentHashMap<String, Object>> serviceRouteMap = new ConcurrentHashMap<>();

    /**
     * Bean类型枚举
     */
    private enum BeanType {
        /**
         * Mapper类型
         */
        MAPPER,

        /**
         * Service类型
         */
        SERVICE,

        /**
         * 未知类型
         */
        UNKNOWN
    }

    /**
     * 扫描并注册带有注解的bean
     * @param applicationContext applicationContext
     * @throws BeansException ex
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        log.info("==================== 开始扫描并注册带@RouteMapper注解的Bean ====================");
        // 获取所有带@RouteMapper注解的Bean
        Map<String, Object> beansWithAnnotation = applicationContext.getBeansWithAnnotation(RouteCustom.class);

        if (beansWithAnnotation.isEmpty()) {
            log.warn("未发现任何带@RouteMapper注解的Bean");
            return;
        }
        log.info("发现 {} 个带@RouteMapper注解的Bean", beansWithAnnotation.size());
        // 开始遍历
        for (Map.Entry<String, Object> entry : beansWithAnnotation.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();

            try {
                Class<?> targetClass = AopUtils.getTargetClass(bean);
                log.debug("处理Bean: {}, 目标类: {}", beanName, targetClass.getName());
                // 判断Bean类型（Mapper 或 Service）
                BeanType beanType = determineBeanType(bean, targetClass);

                if (beanType == BeanType.UNKNOWN) {
                    log.warn("无法识别Bean类型: {}, 跳过注册", beanName);
                    continue;
                }

                // 检查类本身的注解
                RouteCustom annotation = targetClass.getAnnotation(RouteCustom.class);
                if (annotation != null) {
                    registerBean(annotation, bean, beanType, beanName);
                }
                // 检查接口注解
                Class<?>[] interfaces = targetClass.getInterfaces();
                for (Class<?> anInterface : interfaces) {
                    annotation = anInterface.getAnnotation(RouteCustom.class);
                    if (annotation != null) {
                        registerBean(annotation, bean, beanType, beanName);
                        break;
                    }
                }
            } catch (Exception ex) {
                log.error("注册Bean失败: beanName={}", beanName, ex);
            }
        }

    }

    /**
     * 判断bean类型
     * @param bean bean实例
     * @param targetClass 目标类
     * @return bean类型
     */
    private BeanType determineBeanType(Object bean, Class<?> targetClass) {
        if (bean instanceof IBaseFaultDetailService) {
            return BeanType.SERVICE;
        }

        if (bean instanceof BaseFaultDetailRouterMapper) {
            return BeanType.MAPPER;
        }
        return BeanType.UNKNOWN;
    }

    /**
     * 注册bean到路由表
     * @param annotation 注解
     * @param bean bean实例
     * @param beanType bean类型
     * @param beanName bean名称
     */
    private void registerBean(RouteCustom annotation, Object bean, Object beanType, String beanName) {
        String module = annotation.moduleName();
        String suffix = annotation.suffix();

        if (StrUtil.isBlank(module)) {
            log.warn("Bean {} 的 @RouteCustom 注解 module 为空，跳过注册", beanName);
            return;
        }

        if (StrUtil.isBlank(suffix)) {
            log.warn("Bean {} 的 @RouteCustom 注解 value(suffix) 为空，跳过注册", beanName);
            return;
        }

        Map<String, ConcurrentHashMap<String, Object>> targetRouteMap =
                (beanType == BeanType.MAPPER) ? mapperRouteMap : serviceRouteMap;

        ConcurrentHashMap<String, Object> routeMap = targetRouteMap.computeIfAbsent(
                module, k -> new ConcurrentHashMap<>()
        );

        // 检查是否已存在
        if (routeMap.containsKey(suffix)) {
            log.warn("路由已存在，将被覆盖: type={}, module={}, suffix={}, oldBean={}, newBean={}",
                    beanType, module, suffix, routeMap.get(suffix).getClass().getSimpleName(),
                    bean.getClass().getSimpleName());
        }
        routeMap.put(suffix, bean);
        String beanTypeName = (beanType == BeanType.MAPPER) ? "Mapper" : "Service";
        log.debug("注册{}: module={}, suffix={}, bean={}",
                beanTypeName, module, suffix, beanName);

    }
    // Mapper相关方法

    /**
     * 根据module和suffix获取mapper
     * @param module 模块名称
     * @param suffix 路由后缀
     * @param mapperType mapperType Mapper类型
     * @return Mapper实例
     * @param <T> Mapper类型泛型
     */
    @SneakyThrows
    public <T> T getMapper(String module, String suffix, Class<T> mapperType) {
        log.info("获取Mapper：module={}, suffix={}, mapperType={}", module, suffix, mapperType);
        if (StrUtil.isBlank(module)) {
            throw new IllegalArgumentException("module is empty");
        }
        if (StrUtil.isBlank(suffix)) {
            throw new IllegalArgumentException("suffix is empty");
        }
        ConcurrentHashMap<String, Object> routeMap = mapperRouteMap.get(module);
        if (routeMap == null) {
            throw new Exception("Mapper 模块未注册：" + module);
        }
        Object rawMapper = routeMap.get(suffix);
        if (rawMapper == null) {
            log.warn("Mapper路由不存在，module={}, suffix={}, 尝试使用默认路由/betaSuffix", module, suffix);
            rawMapper = routeMap.get(suffix);
        }
        if (rawMapper == null) {
            throw new Exception(String.format("Mapper未注册：module=%s, suffix=%s", module, suffix));
        }

        // 类型检查转化
        if (!mapperType.isInstance(rawMapper)) {
            throw new Exception(String.format("Mapper类型不匹配，期望类型：%s，实际类型：%s", mapperType.getSimpleName(), rawMapper.getClass().getSimpleName()));
        }

        log.debug("成功获取Mapper：{}",rawMapper.getClass().getSimpleName());
        return mapperType.cast(rawMapper);
    }

    /**
     * 根据路由获取mapper
     * @param routeKey 路由标识
     * @param mapperType mapper类型
     * @return mapper实例
     * @param <T> mapper类型泛型
     */
    public <T> T getMapperByRoute(String routeKey, Class<T> mapperType) {
        log.debug("根据路由key获取Mapper：routeKey={}, mapperType={}", routeKey, mapperType);
        RouteConfig config = routeConfigManager.getRouteConfig(routeKey);
        log.debug("路由配置：module={}, suffix={}, mapperType={}", config.getModule(), config.getSuffix(), mapperType);
        return getMapper(config.getModule(), config.getSuffix(), mapperType);
    }

    /**
     * 从上下文自动获取Mapper 直接使用RouteContext中的路由信息
     * @param mapperType Mapper类型
     * @return mapper实例
     * @param <T> mapper类型泛型
     */
    public <T> T getMapperFromContext(Class<T> mapperType) {
        String moduleName = RouteContext.getModuleName();
        String routeSuffix = RouteContext.getRouteSuffix();
        if (StrUtil.isBlank(moduleName) || StrUtil.isBlank(routeSuffix)) {
            throw new IllegalArgumentException("moduleName or routeSuffix is empty");
        }

        log.debug("从上下文获取Mapper：module={}, suffix={}, mapperType={}", moduleName, routeSuffix, mapperType);
        return getMapper(moduleName, routeSuffix, mapperType);
    }

    // Service相关方法

    /**
     * 根据module和suffix获取service
     * @param module 模块名称
     * @param suffix 路由后缀
     * @param serviceType service类型
     * @return service实例
     * @param <T> service类型泛型
     */
    @SneakyThrows
    public <T> T getService(String module, String suffix, Class<T> serviceType) {
        log.info("获取Service：module={}, suffix={}, mapperType={}", module, suffix, serviceType);
        if (StrUtil.isBlank(module)) {
            throw new IllegalArgumentException("module is empty");
        }
        if (StrUtil.isBlank(suffix)) {
            throw new IllegalArgumentException("suffix is empty");
        }
        ConcurrentHashMap<String, Object> routeMap = serviceRouteMap.get(module);
        if (routeMap == null) {
            throw new Exception("Service 模块未注册：" + module);
        }
        Object rawService = routeMap.get(suffix);
        if (rawService == null) {
            log.warn("Service路由不存在，module={}, suffix={}, 尝试使用默认路由/betaSuffix", module, suffix);
            rawService = routeMap.get(suffix);
        }
        if (rawService == null) {
            throw new Exception(String.format("Service未注册：module=%s, suffix=%s", module, suffix));
        }

        // 类型检查转化
        if (!serviceType.isInstance(rawService)) {
            throw new Exception(String.format("Mapper类型不匹配，期望类型：%s，实际类型：%s", serviceType.getSimpleName(), rawService.getClass().getSimpleName()));
        }

        log.debug("成功获取Service：{}",rawService.getClass().getSimpleName());
        return serviceType.cast(rawService);
    }

    /**
     * 根据路由获取service
     * @param routeKey 路由标识
     * @param serviceType service类型
     * @return service实例
     * @param <T> service类型泛型
     */
    public <T> T getServiceByRoute(String routeKey, Class<T> serviceType) {
        log.debug("根据路由key获取Service：routeKey={}, serviceType={}", routeKey, serviceType);
        RouteConfig config = routeConfigManager.getRouteConfig(routeKey);
        log.debug("路由配置：module={}, suffix={}, serviceType={}", config.getModule(), config.getSuffix(), serviceType);
        return getService(config.getModule(), config.getSuffix(), serviceType);
    }

    /**
     * 从上下文自动获取service 直接使用RouteContext中的路由信息
     * @param serviceType service类型
     * @return service实例
     * @param <T> service类型泛型
     */
    public <T> T getServiceFromContext(Class<T> serviceType) {
        String moduleName = RouteContext.getModuleName();
        String routeSuffix = RouteContext.getRouteSuffix();
        if (StrUtil.isBlank(moduleName) || StrUtil.isBlank(routeSuffix)) {
            throw new IllegalArgumentException("moduleName or routeSuffix is empty");
        }

        log.debug("从上下文获取Service：module={}, suffix={}, mapperType={}", moduleName, routeSuffix, serviceType);
        return getService(moduleName, routeSuffix, serviceType);
    }

    /**
     * 清空所有缓存
     */
    public void clearAll() {
        mapperRouteMap.clear();
        serviceRouteMap.clear();
        log.info("已清空所有路由");
    }
}

