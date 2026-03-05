package org.cabbage.codedemo.route.config;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.interceptor.DimensionInterceptor;
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

    private final DimensionInterceptor dimensionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(dimensionInterceptor)
                .addPathPatterns("/beta/**", "/comm/**")
                .order(1);
    }
}
