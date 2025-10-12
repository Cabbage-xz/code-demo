package org.cabbage.codedemo.route.controller;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.common.Result;
import org.cabbage.codedemo.route.service.impl.FaultDetailFacadeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author xzcabbage
 * @since 2025/10/12
 */
@RestController
@RequiredArgsConstructor
public class FaultController {

    private final FaultDetailFacadeService faultDetailFacadeService;

    /**
     * 公共方法 --beta
     * @return string
     */
    @GetMapping("/fault/distribution/beta_fault")
    public Result<String> queryBetaFaultDistribution() {
        return Result.success(faultDetailFacadeService.queryFaultDetail());
    }

    /**
     * 公共方法 --comm
     * @return string
     */
    @GetMapping("/fault/distribution/comm_fault")
    public Result<String> queryCommFaultDistribution() {
        return Result.success(faultDetailFacadeService.queryFaultDetail());
    }

    /**
     * 公共方法 --beta other app
     * @return string
     */
    @GetMapping("/fault/detail/beta_other_app")
    public Result<String> queryBetaOtherAppFaultDetail() {
        return Result.success(faultDetailFacadeService.queryFaultDetail());
    }

    /**
     * 公共方法 --comm other app
     * @return string
     */
    @GetMapping("/fault/detail/comm_other_app")
    public Result<String> queryCommOtherAppFaultDetail() {
        return Result.success(faultDetailFacadeService.queryFaultDetail());
    }

    /**
     * 公共方法 --beta
     * @return string
     */
    @GetMapping("/fault/count/beta_fault")
    public Result<String> countBetaFaults() {
        return Result.success(faultDetailFacadeService.countFaults());
    }

    /**
     * 公共方法 --beta other app
     * @return string
     */
    @GetMapping("/fault/count/beta_other_app")
    public Result<String> countBetaOtherAppFaults() {
        return Result.success(faultDetailFacadeService.countFaults());
    }

}
