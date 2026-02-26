package org.cabbage.codedemo.faultdatasync.mq.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.cabbage.codedemo.faultdatasync.model.FaultDataBatchMessage;
import org.cabbage.codedemo.faultdatasync.service.SyncTaskRecordService;
import org.springframework.stereotype.Component;

/**
 * 死信队列消费者（DLQ）
 * <p>
 * 当 FaultDataConsumer 超过最大重试次数后，消息进入 RocketMQ DLQ。
 * 此消费者负责：
 * <ol>
 *   <li>将 sync_task_record 状态更新为 FAILED，记录错误信息</li>
 *   <li>（可扩展）触发告警通知（邮件、钉钉、Prometheus Alert 等）</li>
 * </ol>
 * <p>
 * RocketMQ DLQ topic 命名规则：%DLQ% + consumerGroup
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "%DLQ%fault-data-sync-consumer",
        consumerGroup = "fault-data-sync-dlq-consumer"
)
public class FaultDataDlqConsumer implements RocketMQListener<FaultDataBatchMessage> {

    private final SyncTaskRecordService syncTaskRecordService;

    @Override
    public void onMessage(FaultDataBatchMessage msg) {
        String errorMsg = String.format(
                "MQ 消息超过最大重试次数进入 DLQ: domain=%s date=%s batchIndex=%d records=%d",
                msg.getDomain(), msg.getDataDate(), msg.getBatchIndex(),
                msg.getRecords() != null ? msg.getRecords().size() : 0);

        log.error("[DLQ] {}", errorMsg);

        // 标记同步任务失败
        syncTaskRecordService.updateFailed(msg.getDomain(), msg.getDataDate(), errorMsg);

        // TODO: 接入告警系统（钉钉/邮件/PagerDuty）
        // alertService.sendAlert(errorMsg);
    }
}
