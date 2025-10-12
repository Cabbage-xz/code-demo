package org.cabbage.codedemo.route.service;

import org.cabbage.codedemo.route.entity.BetaOtherAppFaultDetailEntity;

/**
 * @author xzcabbage
 * @since 2025/10/12
 * 继承基础接口 编写beta三方独有方法
 */
public interface IBetaOtherAppFaultDetailService extends IBaseFaultDetailService<BetaOtherAppFaultDetailEntity> {

    String queryBetaOtherAppUnique();
}
