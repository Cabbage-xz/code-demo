package org.cabbage.codedemo.route.controller;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.common.Result;
import org.cabbage.codedemo.route.service.FaultDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author xzcabbage
 * @since 2025/10/10
 * 六个维度的公共端点统一入口
 * URL 约定：/{module}/{domain}/{endpoint}
 * 拦截器已根据 module+domain 将正确的表名写入 DimensionContext，此处无需感知维度细节
 */
@RestController
@RequiredArgsConstructor
public class FaultCommonController {

    private final FaultDetailService faultDetailService;

    @GetMapping("/{module}/{domain}/distribution")
    public Result<String> queryFaultDistribution(
            @PathVariable String module, @PathVariable String domain) {
        return Result.success(faultDetailService.queryFaultDistribution());
    }

    @GetMapping("/{module}/{domain}/detail")
    public Result<String> queryFaultDetail(
            @PathVariable String module, @PathVariable String domain) {
        return Result.success(faultDetailService.queryFaultDetail());
    }

    @GetMapping("/{module}/{domain}/count")
    public Result<String> countFaults(
            @PathVariable String module, @PathVariable String domain) {
        return Result.success(faultDetailService.countFaults());
    }
}
