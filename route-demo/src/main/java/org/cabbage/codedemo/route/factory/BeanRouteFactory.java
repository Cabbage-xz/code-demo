package org.cabbage.codedemo.route.factory;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.route.annotation.RouteMapper;
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
     * 第一层key: module (如: faultDetailMapper)
     * 第二层key: suffix (如: /betaSuffix)
     * value: Mapper实例
     *
     * 示例结构:
     * {
     *   "faultDetailMapper": {
     *     "/betaSuffix": BetaFaultDetailMapper实例,
     *     "/commSuffix": CommFaultDetailMapper实例
     *   },
     *   "otherAppFaultDetailMapper": {
     *     "/betaSuffix": BetaOtherAppFaultDetailMapper实例,
     *     "/commSuffix": CommOtherAppFaultDetailMapper实例
     *   }
     * }
     */
    private final Map<String, ConcurrentHashMap<String, Object>> mapperRouteMap = new ConcurrentHashMap<>();

    /**
     * Service路由映射表
     * 第一层key: module (如: faultDetailService)
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
        Map<String, Object> beansWithAnnotation = applicationContext.getBeansWithAnnotation(RouteMapper.class);

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
                RouteMapper annotation = targetClass.getAnnotation(RouteMapper.class);
                if (annotation != null) {
                    registerBean(annotation, bean, beanType, beanName);
                }
                // 检查接口注解
                Class<?>[] interfaces = targetClass.getInterfaces();
                for (Class<?> anInterface : interfaces) {
                    annotation = anInterface.getAnnotation(RouteMapper.class);
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
    private void registerBean(RouteMapper annotation, Object bean, Object beanType, String beanName) {
        String module = annotation.module();
        String suffix = annotation.value();

        if (StrUtil.isBlank(module)) {
            log.warn("Bean {} 的 @RouteMapper 注解 module 为空，跳过注册", beanName);
            return;
        }

        if (StrUtil.isBlank(suffix)) {
            log.warn("Bean {} 的 @RouteMapper 注解 value(suffix) 为空，跳过注册", beanName);
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
    // todo 获取mapper和service

}
