package org.cabbage.codedemo.route.controller.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.common.Result;
import org.cabbage.codedemo.route.service.ext.CommPerfExtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author xzcabbage
 * Comm性能故障 独有端点
 */
@RestController
@RequestMapping("/comm/perf")
@RequiredArgsConstructor
public class CommPerfExtController {

    private final CommPerfExtService service;

    @GetMapping("/perf-metrics")
    public Result<String> queryPerfMetrics() {
        return Result.success(service.queryPerfMetrics());
    }
}
