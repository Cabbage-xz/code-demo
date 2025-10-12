package org.cabbage.codedemo.route.service.impl;

import org.cabbage.codedemo.route.annotation.RouteCustom;
import org.cabbage.codedemo.route.entity.BetaFaultDetailEntity;
import org.cabbage.codedemo.route.mapper.BetaFaultDetailRouterMapper;
import org.cabbage.codedemo.route.service.IBetaFaultDetailService;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * @since 2025/10/12
 */
@RouteCustom(suffix = "/betaSuffix", moduleName = "faultDetail")
@Service
public class BetaFaultDetailServiceImpl
        extends BaseFaultDetailServiceImpl<BetaFaultDetailRouterMapper, BetaFaultDetailEntity>
        implements IBetaFaultDetailService {


    @Override
    public String queryBetaUnique() {
        return "queryBetaUnique";
    }
}
