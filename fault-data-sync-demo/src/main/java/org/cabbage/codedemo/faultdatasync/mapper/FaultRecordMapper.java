package org.cabbage.codedemo.faultdatasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.cabbage.codedemo.faultdatasync.entity.FaultRecordEntity;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface FaultRecordMapper extends BaseMapper<FaultRecordEntity> {

    /**
     * 批量插入（INSERT IGNORE），防止 MQ 重投导致重复写入
     */
    int batchInsert(@Param("list") List<FaultRecordEntity> list);

    /**
     * 按领域和日期全量删除，用于重同步前清空旧数据
     */
    int deleteByDomainAndDate(@Param("domain") String domain, @Param("dataDate") LocalDate dataDate);
}
