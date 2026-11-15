package cn.swjtu.zhixing.campus;

import java.time.DayOfWeek;
import java.util.List;

/**
 * 开放限制时段：命中该时段时连接不可用（支持跨夜，如 23:00–06:00）。
 *
 * <p>{@code days} 为空表示每天生效；跨夜时段按开始日判断（例如 MONDAY 23:00–06:00
 * 同时覆盖周一 23:00–24:00 与周二 00:00–06:00）。无证据时生产数据不填写任何时段，
 * 而不是假设全天开放。</p>
 */
public record CampusConnectorTimeRestriction(
        List<DayOfWeek> days,
        String from,
        String to,
        String reason) {

    public CampusConnectorTimeRestriction {
        days = days == null ? List.of() : List.copyOf(days);
    }
}
