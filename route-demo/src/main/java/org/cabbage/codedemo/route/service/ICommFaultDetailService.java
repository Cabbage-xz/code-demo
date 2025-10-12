package org.cabbage.codedemo.route.service;

import org.cabbage.codedemo.route.entity.CommFaultDetailEntity;

/**
 * @author xzcabbage
 * @since 2025/10/12
 * 继承基础接口 添加comm独有方法
 */
public interface ICommFaultDetailService extends IBaseFaultDetailService<CommFaultDetailEntity> {

    String queryCommUnique();
}
