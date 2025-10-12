package org.cabbage.codedemo.route.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.cabbage.codedemo.route.annotation.RouteCustom;
import org.cabbage.codedemo.route.entity.BetaOtherAppFaultDetailEntity;

/**
 * @author xzcabbage
 * @since 2025/10/12
 * beta三方应用故障接口
 */
@Mapper
@RouteCustom(suffix = "/betaSuffix", moduleName = "otherAppFaultDetail")
public interface BetaOtherAppFaultDetailRouterMapper
        extends BaseMapper<BetaOtherAppFaultDetailEntity>, BaseFaultDetailRouterMapper {
}
