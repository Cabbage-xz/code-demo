package org.cabbage.codedemo.route.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.cabbage.codedemo.route.annotation.RouteCustom;
import org.cabbage.codedemo.route.entity.BetaFaultDetailEntity;

/**
 * @author xzcabbage
 * @since 2025/10/12
 * Beta故障mapper
 */
@Mapper
@RouteCustom(suffix = "/betaSuffix", moduleName = "faultDetail")
public interface BetaFaultDetailRouterMapper
        extends BaseMapper<BetaFaultDetailEntity>, BaseFaultDetailRouterMapper {
}
