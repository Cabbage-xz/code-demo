package org.cabbage.codedemo.route.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.cabbage.codedemo.route.annotation.RouteCustom;
import org.cabbage.codedemo.route.entity.CommFaultDetailEntity;

/**
 * @author xzcabbage
 * @since 2025/10/12
 * comm故障接口
 */
@Mapper
@RouteCustom(suffix = "/commSuffix", moduleName = "faultDetail")
public interface CommFaultDetailRouterMapper
        extends BaseMapper<CommFaultDetailEntity>, BaseFaultDetailRouterMapper {
}
