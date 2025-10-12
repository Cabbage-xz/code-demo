package org.cabbage.codedemo.route.service;

import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 故障service基础接口 定义通用方法
 */
public interface IBaseFaultDetailService<T> extends IService<T> {

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
