package org.cabbage.codedemo.route.controller.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.common.Result;
import org.cabbage.codedemo.route.service.ext.BetaNormalExtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author xzcabbage
 * Beta普通故障 独有端点
 */
@RestController
@RequestMapping("/beta/normal")
@RequiredArgsConstructor
public class BetaNormalExtController {

    private final BetaNormalExtService service;

    @GetMapping("/normal-trend")
    public Result<String> queryNormalFaultTrend() {
        return Result.success(service.queryNormalFaultTrend());
    }
}
