package org.cabbage.codedemo.route.service;

import org.cabbage.codedemo.route.entity.CommOtherAppFaultDetailEntity;

/**
 * @author xzcabbage
 * @since 2025/10/12
 * 继承基础接口 添加comm三方独有方法
 */
public interface ICommOtherAppFaultDetailService extends IBaseFaultDetailService<CommOtherAppFaultDetailEntity> {

    String queryCommOtherAppUnique();
}
