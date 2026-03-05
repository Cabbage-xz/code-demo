package org.cabbage.codedemo.route.config;

import com.zaxxer.hikari.HikariDataSource;
import org.cabbage.codedemo.route.routing.DynamicRoutingDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 多数据源配置：将 beta / comm 两个数据源注册到 DynamicRoutingDataSource
 */
@Configuration
public class DynamicDataSourceConfig {

    @Bean("betaDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.beta")
    public DataSource betaDataSource() {
        return new HikariDataSource();
    }

    @Bean("commDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.comm")
    public DataSource commDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @Primary
    public DynamicRoutingDataSource dynamicDataSource() {
        DynamicRoutingDataSource dynamic = new DynamicRoutingDataSource();

        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("beta", betaDataSource());
        targetDataSources.put("comm", commDataSource());

        dynamic.setTargetDataSources(targetDataSources);
        dynamic.setDefaultTargetDataSource(betaDataSource());
        dynamic.afterPropertiesSet();
        return dynamic;
    }
}
