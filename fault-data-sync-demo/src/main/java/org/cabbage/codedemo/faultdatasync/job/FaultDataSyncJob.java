package org.cabbage.codedemo.faultdatasync.job;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.faultdatasync.service.FaultSyncService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * PowerJob 故障数据同步任务处理器
 * <p>
 * 每个领域在 PowerJob 中配置独立的定时任务，均指向本处理器。
 * 每次触发只处理一个领域的 syncDays 天数据（D-1 至 D-syncDays）。
 * <p>
 * instanceParams 示例（JSON 字符串）：
 * <pre>
 * {"domain": "domain_a", "syncDays": 5}
 * </pre>
 * 领域级并行由 PowerJob 并发调度多个任务实例实现；
 * 日期级并行（5 个日期）通过 CompletableFuture + 共享有界线程池实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaultDataSyncJob implements BasicProcessor {

    private final FaultSyncService faultSyncService;

    @Qualifier("syncExecutor")
    private final Executor syncExecutor;

    @Value("${fault-sync.sync-days:5}")
    private int defaultSyncDays;

    @Override
    public ProcessResult process(TaskContext context) throws Exception {
        log.info("[SyncJob] 任务触发，instanceId={} params={}",
                context.getInstanceId(), context.getJobParams());

        String domain = parseDomain(context.getJobParams());
        int syncDays = parseSyncDays(context.getJobParams());
        List<LocalDate> dates = buildSyncDates(syncDays);

        log.info("[SyncJob] 领域={} 同步天数={} 总任务数={}", domain, syncDays, dates.size());

        // 对单个领域的所有日期并发提交到共享有界线程池
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (LocalDate date : dates) {
            final LocalDate dt = date;
            futures.add(CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            faultSyncService.syncDomainDate(domain, dt);
                            return "OK:" + domain + "_" + dt;
                        } catch (Exception e) {
                            log.error("[SyncJob] 任务失败 domain={} date={}", domain, dt, e);
                            return "FAIL:" + domain + "_" + dt + ":" + e.getMessage();
                        }
                    },
                    syncExecutor
            ));
        }

        // 等待所有日期任务完成，统计结果
        List<String> results = futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        return "FAIL:future_exception:" + e.getMessage();
                    }
                })
                .collect(Collectors.toList());

        long failCount = results.stream().filter(r -> r.startsWith("FAIL")).count();
        long successCount = results.size() - failCount;

        String summary = String.format("成功=%d 失败=%d 总计=%d", successCount, failCount, results.size());
        log.info("[SyncJob] 完成，{}", summary);

        if (failCount > 0) {
            List<String> failDetails = results.stream()
                    .filter(r -> r.startsWith("FAIL"))
                    .collect(Collectors.toList());
            return new ProcessResult(false, summary + " | 失败详情: " + failDetails);
        }
        return new ProcessResult(true, summary);
    }

    /**
     * 从 jobParams 中解析单个领域名称，若未提供则抛出异常。
     */
    private String parseDomain(String jobParams) {
        if (jobParams != null && !jobParams.isBlank()) {
            try {
                cn.hutool.json.JSONObject json = JSONUtil.parseObj(jobParams);
                if (json.containsKey("domain")) {
                    String domain = json.getStr("domain");
                    if (domain != null && !domain.isBlank()) {
                        return domain;
                    }
                }
            } catch (Exception e) {
                log.warn("[SyncJob] 解析 jobParams 失败: {}", e.getMessage());
            }
        }
        throw new IllegalStateException("jobParams 中未找到有效的 domain，请检查 PowerJob 任务配置");
    }

    private int parseSyncDays(String jobParams) {
        if (jobParams != null && !jobParams.isBlank()) {
            try {
                cn.hutool.json.JSONObject json = JSONUtil.parseObj(jobParams);
                if (json.containsKey("syncDays")) {
                    return json.getInt("syncDays");
                }
            } catch (Exception ignored) {
            }
        }
        return defaultSyncDays;
    }

    /**
     * 生成 D-1 至 D-syncDays 的日期列表（含历史重同步）
     */
    private List<LocalDate> buildSyncDates(int syncDays) {
        List<LocalDate> dates = new ArrayList<>(syncDays);
        LocalDate today = LocalDate.now();
        for (int i = 1; i <= syncDays; i++) {
            dates.add(today.minusDays(i));
        }
        return dates;
    }
}
