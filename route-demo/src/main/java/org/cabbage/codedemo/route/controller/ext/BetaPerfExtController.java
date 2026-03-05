package org.cabbage.codedemo.route.controller.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.common.Result;
import org.cabbage.codedemo.route.service.ext.BetaPerfExtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author xzcabbage
 * Beta性能故障 独有端点
 */
@RestController
@RequestMapping("/beta/perf")
@RequiredArgsConstructor
public class BetaPerfExtController {

    private final BetaPerfExtService service;

    @GetMapping("/perf-metrics")
    public Result<String> queryPerfMetrics() {
        return Result.success(service.queryPerfMetrics());
    }
}
