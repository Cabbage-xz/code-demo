package org.cabbage.codedemo.route.mapper.ext;

import org.apache.ibatis.annotations.Mapper;

/**
 * @author xzcabbage
 * Beta三方故障 独有 Mapper
 */
@Mapper
public interface BetaThirdExtMapper {
    String queryThirdPartyRate();
}
