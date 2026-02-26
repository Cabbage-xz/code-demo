package org.cabbage.codedemo.faultdatasync.mq.consumer;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.cabbage.codedemo.faultdatasync.entity.FaultRecordEntity;
import org.cabbage.codedemo.faultdatasync.mapper.FaultRecordMapper;
import org.cabbage.codedemo.faultdatasync.model.FaultDataBatchMessage;
import org.cabbage.codedemo.faultdatasync.model.FaultRecordDTO;
import org.cabbage.codedemo.faultdatasync.service.SyncTaskRecordService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 故障数据 MQ 消费者
 * <p>
 * 消费每批 5000 条故障数据，按 {@code dbBatchSize} 分批执行 INSERT IGNORE 写入 DB。
 * 写入成功后，通知 SyncTaskRecordService 更新已完成批次计数。
 * <p>
 * 重试策略：maxReconsumeTimes = 3，超出后消息进入 DLQ（由 FaultDataDlqConsumer 处理）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${fault-sync.mq.topic:fault-data-sync-topic}",
        consumerGroup = "${fault-sync.mq.consumer-group:fault-data-sync-consumer}",
        maxReconsumeTimes = 3
)
public class FaultDataConsumer implements RocketMQListener<FaultDataBatchMessage> {

    private final FaultRecordMapper faultRecordMapper;
    private final SyncTaskRecordService syncTaskRecordService;

    @Value("${fault-sync.batch-size:1000}")
    private int dbBatchSize;

    @Override
    public void onMessage(FaultDataBatchMessage msg) {
        log.info("[Consumer] 收到消息 domain={} date={} batchIndex={} records={}",
                msg.getDomain(), msg.getDataDate(), msg.getBatchIndex(), msg.getRecords().size());

        List<FaultRecordEntity> entities = convertToEntities(msg);

        // 按 dbBatchSize 分批 INSERT IGNORE，避免单条 SQL 过长
        List<List<FaultRecordEntity>> partitions = CollUtil.split(entities, dbBatchSize);
        for (List<FaultRecordEntity> batch : partitions) {
            faultRecordMapper.batchInsert(batch);
        }

        log.info("[Consumer] domain={} date={} batchIndex={} 写入完成，共 {} 条",
                msg.getDomain(), msg.getDataDate(), msg.getBatchIndex(), entities.size());

        // 通知进度跟踪：已完成批次 +1，若全部完成则自动置为 SUCCESS
        syncTaskRecordService.incrementCompletedBatch(msg.getDomain(), msg.getDataDate());
    }

    private List<FaultRecordEntity> convertToEntities(FaultDataBatchMessage msg) {
        return msg.getRecords().stream()
                .map(dto -> FaultRecordEntity.builder()
                        .domain(dto.getDomain())
                        .dataDate(dto.getDataDate())
                        .rank((int) dto.getRank())
                        .faultType(dto.getFaultType())
                        .deviceId(dto.getDeviceId())
                        .faultDetail(dto.getFaultDetail())
                        .build())
                .collect(Collectors.toList());
    }
}
