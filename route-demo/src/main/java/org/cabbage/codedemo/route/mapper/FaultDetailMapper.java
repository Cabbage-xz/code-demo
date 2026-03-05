package org.cabbage.codedemo.route.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.cabbage.codedemo.route.entity.FaultDetailEntity;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 故障详情统一 Mapper（六张表共用）
 * 实际操作的表名由 DynamicTableNameInnerInterceptor 在运行时从 DimensionContext 中读取并替换
 */
@Mapper
public interface FaultDetailMapper extends BaseMapper<FaultDetailEntity> {

    String queryFaultDistribution();

    String queryFaultDetail();

    String countFaults();
}
