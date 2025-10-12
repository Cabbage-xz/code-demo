package org.cabbage.codedemo.route.service.impl;

import org.cabbage.codedemo.route.annotation.RouteCustom;
import org.cabbage.codedemo.route.entity.CommOtherAppFaultDetailEntity;
import org.cabbage.codedemo.route.mapper.CommOtherAppFaultDetailRouterMapper;
import org.cabbage.codedemo.route.service.ICommOtherAppFaultDetailService;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * @since 2025/10/12
 */
@RouteCustom(suffix = "/commSuffix", moduleName = "otherAppFaultDetail")
@Service
public class CommOtherAppFaultDetailServiceImpl
    extends BaseFaultDetailServiceImpl<CommOtherAppFaultDetailRouterMapper, CommOtherAppFaultDetailEntity>
    implements ICommOtherAppFaultDetailService {


    @Override
    public String queryCommOtherAppUnique() {
        return "queryCommOtherAppUnique";
    }

}
