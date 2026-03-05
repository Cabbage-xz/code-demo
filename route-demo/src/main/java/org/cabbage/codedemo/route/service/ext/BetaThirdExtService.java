package org.cabbage.codedemo.route.service.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.mapper.ext.BetaThirdExtMapper;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * Beta三方故障 独有业务逻辑
 */
@Service
@RequiredArgsConstructor
public class BetaThirdExtService {

    private final BetaThirdExtMapper mapper;

    public String queryThirdPartyRate() {
        return mapper.queryThirdPartyRate();
    }
}
