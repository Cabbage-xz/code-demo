package org.cabbage.codedemo.route.service;

import org.cabbage.codedemo.route.entity.BetaFaultDetailEntity;

/**
 * @author xzcabbage
 * @since 2025/10/12
 * 继承基础接口 添加beta独有方法
 */
public interface IBetaFaultDetailService extends IBaseFaultDetailService<BetaFaultDetailEntity> {

    String queryBetaUnique();
}
