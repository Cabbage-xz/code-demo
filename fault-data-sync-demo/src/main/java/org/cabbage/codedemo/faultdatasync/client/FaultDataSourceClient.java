package org.cabbage.codedemo.faultdatasync.client;

import org.cabbage.codedemo.faultdatasync.model.FaultRecordDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * 原数据源接口定义
 * <p>
 * 接口语义：按 rank 游标翻页拉取指定领域、指定日期的故障记录。
 * 终止条件：返回列表 size < pageSize。
 */
public interface FaultDataSourceClient {

    /**
     * 拉取一批故障数据
     *
     * @param domain   数据领域
     * @param date     数据所属日期
     * @param lastRank 上一批最大 rank（首次传 0）
     * @param pageSize 每批拉取数量（通常为 5000）
     * @return 故障记录列表，按 rank 升序排列；空列表表示无数据或已到末尾
     */
    List<FaultRecordDTO> pull(String domain, LocalDate date, long lastRank, int pageSize);
}
