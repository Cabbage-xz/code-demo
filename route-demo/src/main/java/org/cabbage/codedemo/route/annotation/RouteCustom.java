package org.cabbage.codedemo.route.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 自定义路由注解
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RouteCustom {
    String suffix();           // 映射的路由后缀,如 "/betaSuffix" 或 "/commSuffix"
    String moduleName() default ""; // 模块名称,如 "faultDetailMapper"
}
