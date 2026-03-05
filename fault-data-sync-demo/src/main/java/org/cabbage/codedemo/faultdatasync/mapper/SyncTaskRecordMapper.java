package org.cabbage.codedemo.faultdatasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.cabbage.codedemo.faultdatasync.entity.SyncTaskRecordEntity;

import java.time.LocalDate;

@Mapper
public interface SyncTaskRecordMapper extends BaseMapper<SyncTaskRecordEntity> {

    /**
     * 原子地将已完成批次数加 1，并在全部完成时将状态置为 SUCCESS。
     * WHERE 条件覆盖 RUNNING/MESSAGES_SENT，解决消费者早于 updateMessagesSent 执行时计数丢失问题。
     */
    int incrementCompletedBatch(@Param("domain") String domain,
                                @Param("dataDate") LocalDate dataDate);

    /**
     * Producer 补偿检查：updateMessagesSent 之后调用。
     * 若所有批次均已在 updateMessagesSent 之前消费完成，由此触发 SUCCESS。
     */
    int checkAndMarkSuccessIfAllDone(@Param("domain") String domain,
                                     @Param("dataDate") LocalDate dataDate);
}
