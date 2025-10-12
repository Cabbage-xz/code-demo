package org.cabbage.codedemo.route.service.impl;

import org.cabbage.codedemo.route.annotation.RouteCustom;
import org.cabbage.codedemo.route.entity.CommFaultDetailEntity;
import org.cabbage.codedemo.route.mapper.CommFaultDetailRouterMapper;
import org.cabbage.codedemo.route.service.ICommFaultDetailService;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * @since 2025/10/12
 */
@RouteCustom(suffix = "/commSuffix", moduleName = "faultDetail")
@Service
public class CommFaultDetailServiceImpl
    extends BaseFaultDetailServiceImpl<CommFaultDetailRouterMapper, CommFaultDetailEntity>
    implements ICommFaultDetailService {


    @Override
    public String queryCommUnique() {
        return "queryCommUnique";
    }
}
