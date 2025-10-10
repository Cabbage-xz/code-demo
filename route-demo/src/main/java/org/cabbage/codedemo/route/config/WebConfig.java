package org.cabbage.codedemo.route.config;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.interceptor.RouteInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author xzcabbage
 * @since 2025/10/10
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RouteInterceptor routeInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(routeInterceptor)
                .addPathPatterns("/**")
                .order(1);
    }
}
