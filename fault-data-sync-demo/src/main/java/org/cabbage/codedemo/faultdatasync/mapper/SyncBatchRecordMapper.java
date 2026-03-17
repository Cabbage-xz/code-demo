package org.cabbage.codedemo.faultdatasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.cabbage.codedemo.faultdatasync.entity.SyncBatchRecordEntity;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SyncBatchRecordMapper extends BaseMapper<SyncBatchRecordEntity> {

    /**
     * INSERT ... ON DUPLICATE KEY UPDATE：记录 pull 成功的批次信息
     */
    void upsertPullSuccess(@Param("domain") String domain,
                           @Param("dataDate") LocalDate dataDate,
                           @Param("batchIndex") int batchIndex,
                           @Param("startRank") long startRank,
                           @Param("endRank") long endRank,
                           @Param("recordCount") int recordCount);

    /**
     * 查询 domain+date 下所有失败批次（pull_status=FAILED 或 insert_status=FAILED），按 batch_index 升序
     */
    List<SyncBatchRecordEntity> findFailed(@Param("domain") String domain,
                                           @Param("dataDate") LocalDate dataDate);
}
