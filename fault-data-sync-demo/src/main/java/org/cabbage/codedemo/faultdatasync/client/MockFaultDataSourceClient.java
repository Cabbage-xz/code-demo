package org.cabbage.codedemo.faultdatasync.client;

import lombok.extern.slf4j.Slf4j;
import org.cabbage.codedemo.faultdatasync.model.FaultRecordDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mock 数据源客户端（Demo 专用）
 * <p>
 * 模拟按 rank 游标分页返回故障数据。
 * 每个 domain+date 组合预设 {@code mockTotalPerDomain} 条记录，
 * 当 lastRank >= mockTotalPerDomain 时返回空列表，触发拉取终止。
 */
@Slf4j
@Component
public class MockFaultDataSourceClient implements FaultDataSourceClient {

    /**
     * 每个 domain+date 的 Mock 数据总量，可通过配置调整。
     * 默认 20000 条（约 4 批次），设为 1000000 可模拟百万顶峰场景。
     */
    @Value("${fault-sync.mock-total-per-domain:20000}")
    private int mockTotalPerDomain;

    @Override
    public List<FaultRecordDTO> pull(String domain, LocalDate date, long lastRank, int pageSize) {
        if (lastRank >= mockTotalPerDomain) {
            log.debug("[MockClient] domain={} date={} lastRank={} 已到末尾，返回空", domain, date, lastRank);
            return List.of();
        }

        long startRank = lastRank + 1;
        long endRank = Math.min(lastRank + pageSize, mockTotalPerDomain);
        int count = (int) (endRank - startRank + 1);

        List<FaultRecordDTO> result = new ArrayList<>(count);
        for (long rank = startRank; rank <= endRank; rank++) {
            result.add(FaultRecordDTO.builder()
                    .domain(domain)
                    .dataDate(date)
                    .rank(rank)
                    .faultType("FAULT_TYPE_" + (rank % 10))
                    .deviceId("DEVICE_" + UUID.randomUUID().toString().substring(0, 8))
                    .faultDetail("Mock fault detail for rank=" + rank)
                    .build());
        }

        log.debug("[MockClient] domain={} date={} lastRank={} 返回 {} 条 (rank {}-{})",
                domain, date, lastRank, result.size(), startRank, endRank);
        return result;
    }
}
