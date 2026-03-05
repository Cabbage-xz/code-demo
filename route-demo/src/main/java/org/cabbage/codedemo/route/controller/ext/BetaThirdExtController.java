package org.cabbage.codedemo.route.controller.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.common.Result;
import org.cabbage.codedemo.route.service.ext.BetaThirdExtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author xzcabbage
 * Beta三方故障 独有端点
 */
@RestController
@RequestMapping("/beta/third")
@RequiredArgsConstructor
public class BetaThirdExtController {

    private final BetaThirdExtService service;

    @GetMapping("/third-rate")
    public Result<String> queryThirdPartyRate() {
        return Result.success(service.queryThirdPartyRate());
    }
}
