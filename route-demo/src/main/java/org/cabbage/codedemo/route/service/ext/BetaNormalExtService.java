package org.cabbage.codedemo.route.service.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.mapper.ext.BetaNormalExtMapper;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * Beta普通故障 独有业务逻辑
 */
@Service
@RequiredArgsConstructor
public class BetaNormalExtService {

    private final BetaNormalExtMapper mapper;

    public String queryNormalFaultTrend() {
        return mapper.queryNormalFaultTrend();
    }
}
