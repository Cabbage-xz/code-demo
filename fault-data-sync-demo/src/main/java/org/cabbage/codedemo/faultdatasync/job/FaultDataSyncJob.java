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
 * 每日由 PowerJob Server 触发，并行同步 20 个领域 × 5 天数据。
 * <p>
 * instanceParams 示例（JSON 字符串）：
 * <pre>
 * {"domains": ["domain_a", "domain_b", ...], "syncDays": 5}
 * </pre>
 * 若未传入，则使用配置文件中的默认值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaultDataSyncJob implements BasicProcessor {

    private final FaultSyncService faultSyncService;

    @Qualifier("syncExecutor")
    private final Executor syncExecutor;

    @Value("${fault-sync.domains:}")
    private List<String> defaultDomains;

    @Value("${fault-sync.sync-days:5}")
    private int defaultSyncDays;

    @Override
    public ProcessResult process(TaskContext context) throws Exception {
        log.info("[SyncJob] 任务触发，instanceId={} params={}",
                context.getInstanceId(), context.getJobParams());

        // 解析领域列表和同步天数（优先使用 instanceParams，否则使用配置默认值）
        List<String> domains = parseDomains(context.getJobParams());
        int syncDays = parseSyncDays(context.getJobParams());
        List<LocalDate> dates = buildSyncDates(syncDays);

        log.info("[SyncJob] 领域数={} 同步天数={} 总任务数={}", domains.size(), syncDays,
                domains.size() * dates.size());

        // 提交所有 domain+date 组合到有界线程池并行执行
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (String domain : domains) {
            for (LocalDate date : dates) {
                final String d = domain;
                final LocalDate dt = date;
                futures.add(CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                faultSyncService.syncDomainDate(d, dt);
                                return "OK:" + d + "_" + dt;
                            } catch (Exception e) {
                                log.error("[SyncJob] 任务失败 domain={} date={}", d, dt, e);
                                return "FAIL:" + d + "_" + dt + ":" + e.getMessage();
                            }
                        },
                        syncExecutor
                ));
            }
        }

        // 等待所有任务完成，统计结果
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

    private List<String> parseDomains(String jobParams) {
        if (jobParams != null && !jobParams.isBlank()) {
            try {
                cn.hutool.json.JSONObject json = JSONUtil.parseObj(jobParams);
                if (json.containsKey("domains")) {
                    return json.getJSONArray("domains").toList(String.class);
                }
            } catch (Exception e) {
                log.warn("[SyncJob] 解析 jobParams 失败，使用默认领域列表: {}", e.getMessage());
            }
        }
        if (defaultDomains == null || defaultDomains.isEmpty()) {
            throw new IllegalStateException("未配置领域列表，请检查 fault-sync.domains 配置或 jobParams");
        }
        return defaultDomains;
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
     * 生成 D-1 至 D-syncDays 的日期列表（含边历史重同步）
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
