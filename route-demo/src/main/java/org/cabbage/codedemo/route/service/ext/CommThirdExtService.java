package org.cabbage.codedemo.route.service.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.mapper.ext.CommThirdExtMapper;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * Comm三方故障 独有业务逻辑
 */
@Service
@RequiredArgsConstructor
public class CommThirdExtService {

    private final CommThirdExtMapper mapper;

    public String queryThirdPartyRate() {
        return mapper.queryThirdPartyRate();
    }
}
