package org.cabbage.codedemo.route.service.impl;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.factory.BeanRouteFactory;
import org.cabbage.codedemo.route.service.IBaseFaultDetailService;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * @since 2025/10/12
 */
@Service
@RequiredArgsConstructor
public class FaultDetailFacadeService {

    private final BeanRouteFactory beanRouteFactory;

    private IBaseFaultDetailService<?> getService() {
        return beanRouteFactory.getServiceFromContext(
                IBaseFaultDetailService.class
        );
    }


    public String queryFaultDistribution() {
        IBaseFaultDetailService<?> service = getService();
        return service.queryFaultDistribution();
    }

    public String queryFaultDetail() {
        IBaseFaultDetailService<?> service = getService();
        return service.queryFaultDetail();
    }

    public String countFaults() {
        IBaseFaultDetailService<?> service = getService();
        return service.countFaults();
    }
}
