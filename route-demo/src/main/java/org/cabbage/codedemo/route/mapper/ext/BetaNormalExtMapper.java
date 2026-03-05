package org.cabbage.codedemo.route.mapper.ext;

import org.apache.ibatis.annotations.Mapper;

/**
 * @author xzcabbage
 * Beta普通故障 独有 Mapper
 * SQL 中使用占位表名 fault_detail，运行时由 DynamicTableNameInnerInterceptor 替换
 */
@Mapper
public interface BetaNormalExtMapper {
    String queryNormalFaultTrend();
}
