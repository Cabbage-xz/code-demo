package org.cabbage.codedemo.route.service.impl;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.cabbage.codedemo.route.mapper.BaseFaultDetailRouterMapper;
import org.cabbage.codedemo.route.service.IBaseFaultDetailService;

/**
 * @author xzcabbage
 * @since 2025/10/12
 * 故障service抽象基类
 * 实现通用方法，子类继承后只需实现各自特有方法
 */
public abstract class BaseFaultDetailServiceImpl<M extends BaseMapper<T>, T>
        extends ServiceImpl<M, T> implements IBaseFaultDetailService<T> {


    protected BaseFaultDetailRouterMapper getRouterMapper() {
        if (baseMapper instanceof BaseFaultDetailRouterMapper) {
            return (BaseFaultDetailRouterMapper) baseMapper;
        }
        return null;
    }

    @Override
    public String queryFaultDistribution() {
        BaseFaultDetailRouterMapper routerMapper = getRouterMapper();
        return routerMapper.queryFaultDistribution();
    }

    @Override
    public String queryFaultDetail() {
        BaseFaultDetailRouterMapper routerMapper = getRouterMapper();
        return routerMapper.queryFaultDetail();
    }

    @Override
    public String countFaults() {
        BaseFaultDetailRouterMapper routerMapper = getRouterMapper();
        return routerMapper.countFaults();
    }
}
