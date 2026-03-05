package org.cabbage.codedemo.route.service.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.mapper.ext.CommPerfExtMapper;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * Comm性能故障 独有业务逻辑
 */
@Service
@RequiredArgsConstructor
public class CommPerfExtService {

    private final CommPerfExtMapper mapper;

    public String queryPerfMetrics() {
        return mapper.queryPerfMetrics();
    }
}
