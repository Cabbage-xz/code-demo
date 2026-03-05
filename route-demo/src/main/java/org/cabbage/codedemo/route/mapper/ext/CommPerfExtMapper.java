package org.cabbage.codedemo.route.mapper.ext;

import org.apache.ibatis.annotations.Mapper;

/**
 * @author xzcabbage
 * Comm性能故障 独有 Mapper
 */
@Mapper
public interface CommPerfExtMapper {
    String queryPerfMetrics();
}
