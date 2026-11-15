package cn.swjtu.zhixing.campus;

import java.util.List;

/**
 * 接驳通行限制：方向、步行许可、校门开放时段与按时段不可用规则。
 *
 * <p>{@code gateOpenHours}/{@code gateOpenHoursStatus} 说明开放时段的证据状态；
 * {@code timeRestrictions} 为空表示“未发现限制”，不代表已确认全天开放。
 * {@code from}/{@code to} 为 Asia/Shanghai 本地时间 HH:mm。</p>
 */
public record CampusConnectorAccessRules(
        String direction,
        String pedestrianAccess,
        String pedestrianAccessBasis,
        String gateOpenHours,
        String gateOpenHoursStatus,
        List<CampusConnectorTimeRestriction> timeRestrictions,
        String timeRestrictionBasis) {

    public CampusConnectorAccessRules {
        timeRestrictions = timeRestrictions == null ? List.of() : List.copyOf(timeRestrictions);
    }
}
