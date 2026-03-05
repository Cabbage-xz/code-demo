package org.cabbage.codedemo.route.controller.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.common.Result;
import org.cabbage.codedemo.route.service.ext.CommThirdExtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author xzcabbage
 * Comm三方故障 独有端点
 */
@RestController
@RequestMapping("/comm/third")
@RequiredArgsConstructor
public class CommThirdExtController {

    private final CommThirdExtService service;

    @GetMapping("/third-rate")
    public Result<String> queryThirdPartyRate() {
        return Result.success(service.queryThirdPartyRate());
    }
}
