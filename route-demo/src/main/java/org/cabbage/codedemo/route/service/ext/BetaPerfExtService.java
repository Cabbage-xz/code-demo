package org.cabbage.codedemo.route.service.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.mapper.ext.BetaPerfExtMapper;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * Beta性能故障 独有业务逻辑
 */
@Service
@RequiredArgsConstructor
public class BetaPerfExtService {

    private final BetaPerfExtMapper mapper;

    public String queryPerfMetrics() {
        return mapper.queryPerfMetrics();
    }
}
