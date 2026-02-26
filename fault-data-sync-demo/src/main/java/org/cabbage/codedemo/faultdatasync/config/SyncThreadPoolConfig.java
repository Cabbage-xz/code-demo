package org.cabbage.codedemo.faultdatasync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 故障数据同步有界线程池配置
 * <p>
 * 用于 FaultDataSyncJob 中 20 个领域并行同步，防止无限制扩张导致 OOM。
 */
@Configuration
public class SyncThreadPoolConfig {

    @Value("${fault-sync.thread-pool-size:20}")
    private int threadPoolSize;

    @Bean(name = "syncExecutor")
    public Executor syncExecutor() {
        return new ThreadPoolExecutor(
                threadPoolSize,
                threadPoolSize,
                60L, TimeUnit.SECONDS,
                // 有界队列：最多允许额外 100 个任务排队
                new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "fault-sync-worker-" + System.nanoTime());
                    t.setDaemon(false);
                    return t;
                },
                // 队列满时在调用方线程执行，起到背压作用
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
