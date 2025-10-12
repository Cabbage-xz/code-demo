package org.cabbage.codedemo.route.mapper;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 故障详情路由Mapper基础接口
 * 所有Mapper继承该接口
 */
public interface BaseFaultDetailRouterMapper {

    /**
     * 查询故障分布
     * @return “queryFaultDistribution”
     */
    String queryFaultDistribution();

    /**
     * 查询故障详情
     * @return “queryFaultDetail”
     */
    String queryFaultDetail();

    /**
     * 查询故障数量
     * @return “countFaults”
     */
    String countFaults();
}
