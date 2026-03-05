package org.cabbage.codedemo.route.controller.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.common.Result;
import org.cabbage.codedemo.route.service.ext.CommNormalExtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author xzcabbage
 * Comm普通故障 独有端点
 */
@RestController
@RequestMapping("/comm/normal")
@RequiredArgsConstructor
public class CommNormalExtController {

    private final CommNormalExtService service;

    @GetMapping("/normal-trend")
    public Result<String> queryNormalFaultTrend() {
        return Result.success(service.queryNormalFaultTrend());
    }
}
