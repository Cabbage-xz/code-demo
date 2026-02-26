package org.cabbage.codedemo.faultdatasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.cabbage.codedemo.faultdatasync.entity.SyncTaskRecordEntity;

import java.time.LocalDate;

@Mapper
public interface SyncTaskRecordMapper extends BaseMapper<SyncTaskRecordEntity> {

    /**
     * 原子地将已完成批次数加 1，并在全部完成时将状态置为 SUCCESS
     */
    int incrementCompletedBatch(@Param("domain") String domain,
                                @Param("dataDate") LocalDate dataDate,
                                @Param("batchCount") int batchCount);
}
