package org.cabbage.codedemo.route.mapper.ext;

import org.apache.ibatis.annotations.Mapper;

/**
 * @author xzcabbage
 * Comm普通故障 独有 Mapper
 */
@Mapper
public interface CommNormalExtMapper {
    String queryNormalFaultTrend();
}
