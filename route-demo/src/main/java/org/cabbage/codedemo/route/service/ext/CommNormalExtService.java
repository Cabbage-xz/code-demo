package org.cabbage.codedemo.route.service.ext;

import lombok.RequiredArgsConstructor;
import org.cabbage.codedemo.route.mapper.ext.CommNormalExtMapper;
import org.springframework.stereotype.Service;

/**
 * @author xzcabbage
 * Comm普通故障 独有业务逻辑
 */
@Service
@RequiredArgsConstructor
public class CommNormalExtService {

    private final CommNormalExtMapper mapper;

    public String queryNormalFaultTrend() {
        return mapper.queryNormalFaultTrend();
    }
}
