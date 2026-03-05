package org.cabbage.codedemo.route.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.cabbage.codedemo.route.entity.FaultDetailEntity;
import org.cabbage.codedemo.route.mapper.FaultDetailMapper;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 故障详情统一 Service，处理六个维度的公共业务逻辑
 * 实际操作的表由 DimensionContext 中的 tableName 决定（拦截器已提前设置）
 */
@Service
public class FaultDetailService extends ServiceImpl<FaultDetailMapper, FaultDetailEntity> {

    public String queryFaultDistribution() {
        return baseMapper.queryFaultDistribution();
    }

    public String queryFaultDetail() {
        return baseMapper.queryFaultDetail();
    }

    public String countFaults() {
        return baseMapper.countFaults();
    }
}
