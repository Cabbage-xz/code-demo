package org.cabbage.codedemo.route.routing;

import org.cabbage.codedemo.route.context.DataSourceContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 动态数据源路由：根据 ThreadLocal 中的 key 返回对应数据源
 */
public class DynamicRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContext.get();
    }
}
