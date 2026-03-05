package org.cabbage.codedemo.route.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import org.cabbage.codedemo.route.context.DimensionContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * MyBatis-Plus 插件配置
 * DynamicTableNameInnerInterceptor：拦截所有 SQL，将占位表名 "fault_detail"
 * 替换为 DimensionContext 中当前请求对应的真实表名
 */
@Configuration
public class MybatisPlusConfig {

    private static final String PLACEHOLDER_TABLE = "fault_detail";

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        DynamicTableNameInnerInterceptor tableNameInterceptor = new DynamicTableNameInnerInterceptor();
        tableNameInterceptor.setTableNameHandler((sql, tableName) -> {
            // 只替换占位表名，避免影响其他表
            if (PLACEHOLDER_TABLE.equals(tableName)) {
                String dynamicTable = DimensionContext.getTableName();
                return dynamicTable != null ? dynamicTable : tableName;
            }
            return tableName;
        });

        interceptor.addInnerInterceptor(tableNameInterceptor);
        return interceptor;
    }
}
