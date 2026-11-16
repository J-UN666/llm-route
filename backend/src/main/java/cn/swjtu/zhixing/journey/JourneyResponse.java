package cn.swjtu.zhixing.journey;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * POST /api/journeys 成功响应（无方案时也是 HTTP 200 + {@link JourneyStatus#NO_ROUTE}）。
 *
 * <p>{@code warnings} 可选：仅当部分校门出校点的城市段查询失败、但仍展示其它成功候选时出现；
 * 全部失败走对应 HTTP 错误响应。{@code campusDataVersion} 同时标识地点数据与接驳数据版本，
 * 格式为 {@code places=<版本>;connectors=<版本>}。</p>
 */
public record JourneyResponse(
        String requestId,
        JourneyStatus status,
        List<JourneyPlan> plans,
        OffsetDateTime queriedAt,
        String campusDataVersion,
        String provider,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

    public JourneyResponse {
        plans = plans == null ? List.of() : List.copyOf(plans);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
