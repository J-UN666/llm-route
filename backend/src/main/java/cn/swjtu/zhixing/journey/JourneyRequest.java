package cn.swjtu.zhixing.journey;

/**
 * POST /api/journeys 请求体。
 *
 * <p>字段与 W1-07 前端、W1-08 任务卡一致。{@code departureTime} 保留字符串形式，
 * 由 {@link JourneyService} 统一解析为带偏移的 ISO-8601 时间，以便返回可区分的
 * {@code DEPARTURE_TIME_INVALID} 错误而不是笼统的反序列化错误。</p>
 *
 * <p>{@code preference} 取值必须为 {@link cn.swjtu.zhixing.transit.TransitPreference}
 * 的枚举名：{@code RECOMMENDED}、{@code LESS_WALKING}、{@code FEWER_TRANSFERS}。</p>
 */
public record JourneyRequest(
        String originId,
        String destinationId,
        String departureTime,
        String preference) {
}
