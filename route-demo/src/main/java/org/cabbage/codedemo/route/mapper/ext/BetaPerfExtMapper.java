package org.cabbage.codedemo.route.mapper.ext;

import org.apache.ibatis.annotations.Mapper;

/**
 * @author xzcabbage
 * Beta性能故障 独有 Mapper
 */
@Mapper
public interface BetaPerfExtMapper {
    String queryPerfMetrics();
}
