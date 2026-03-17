package org.cabbage.codedemo.faultdatasync.mq.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.cabbage.codedemo.faultdatasync.model.FaultDataBatchMessage;
import org.cabbage.codedemo.faultdatasync.model.FaultRecordDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 故障数据 MQ 生产者
 * <p>
 * 每次 5k 拉取结果封装为一条 MQ 消息发送到 fault-data-sync-topic。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaultDataProducer {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${fault-sync.mq.topic:fault-data-sync-topic}")
    private String topic;

    /**
     * 发送一批故障数据到 MQ
     *
     * @param domain     数据领域
     * @param dataDate   数据所属日期
     * @param batchIndex 批次序号（从 0 开始）
     * @param startRank  本批 pull 的起始 rank
     * @param records    本批次记录列表
     */
    public void sendBatch(String domain, LocalDate dataDate, int batchIndex,
                          long startRank, List<FaultRecordDTO> records) {
        FaultDataBatchMessage message = FaultDataBatchMessage.builder()
                .domain(domain)
                .dataDate(dataDate)
                .batchIndex(batchIndex)
                .startRank(startRank)
                .records(records)
                .build();

        String messageKey = domain + "_" + dataDate + "_" + batchIndex;
        rocketMQTemplate.syncSend(topic, MessageBuilder.withPayload(message)
                .setHeader(RocketMQHeaders.KEYS, messageKey)
                .build());

        log.info("[Producer] 发送消息 topic={} key={} batchIndex={} startRank={} records={}",
                topic, messageKey, batchIndex, startRank, records.size());
    }
}
