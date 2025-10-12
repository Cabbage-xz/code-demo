package org.cabbage.codedemo.route.service.impl;

import org.cabbage.codedemo.route.annotation.RouteCustom;
import org.cabbage.codedemo.route.entity.BetaOtherAppFaultDetailEntity;
import org.cabbage.codedemo.route.mapper.BetaOtherAppFaultDetailRouterMapper;
import org.cabbage.codedemo.route.service.IBetaOtherAppFaultDetailService;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * @since 2025/10/12
 */
@RouteCustom(suffix = "/betaSuffix", moduleName = "otherAppFaultDetail")
@Service
public class BetaOtherAppFaultDetailServiceImpl
    extends BaseFaultDetailServiceImpl<BetaOtherAppFaultDetailRouterMapper, BetaOtherAppFaultDetailEntity>
    implements IBetaOtherAppFaultDetailService {


    @Override
    public String queryBetaOtherAppUnique() {
        return "queryBetaOtherAppUnique";
    }
}
