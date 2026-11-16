package cn.swjtu.zhixing.journey;

import cn.swjtu.zhixing.transit.TransitPreference;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全程候选整理：先合并本次查询实际获得的所有候选并去重，再按偏好排序，最后最多保留
 * {@value #MAX_PLANS} 条。比较只基于本次查询实际返回的候选；候选不足三条时按实际数量返回，
 * 不为凑数量构造路线。
 *
 * <p>排序规则：依次比较下列维度的升序，未知值 {@code null} 排在同一维度的已知值之后；
 * 各维度全部相等时再比较 {@code planId}，保证顺序稳定。preference 为 {@code null} 时
 * 按 {@link TransitPreference#RECOMMENDED} 处理。</p>
 * <ul>
 *   <li>{@code LESS_WALKING}：{@code totalWalkingMeters}、{@code transferCount}、
 *       {@code totalDurationSeconds}、{@code planId}；</li>
 *   <li>{@code FEWER_TRANSFERS}：{@code transferCount}、{@code totalWalkingMeters}、
 *       {@code totalDurationSeconds}、{@code planId}；</li>
 *   <li>{@code RECOMMENDED}：{@code totalDurationSeconds}、{@code transferCount}、
 *       {@code totalWalkingMeters}、{@code planId}。</li>
 * </ul>
 *
 * <p>去重签名与 planId 共用同一路线身份：{@code gateExitPoint} 的身份（优先稳定
 * {@code pointId}，缺失时用坐标系与经纬度），以及按分段顺序排列的每个乘车段（非
 * {@code WALK}/{@code CAMPUS_WALK} 段）的线路 ID（缺失时用线路名）、方向、上车站、
 * 下车站。步行段不进入签名，因此同一出校点、同一乘车顺序的分段组合视为重复方案，即使
 * 步行距离或总耗时不同。重复项保留按当前偏好比较更优的一条，并原样保留其稳定
 * {@code planId}；不同出校点、不同线路或方向、不同上下车站仍为不同方案。签名不含提供方
 * 返回序号，因此提供方仅交换候选顺序不会改变任何一条路线的 planId。</p>
 */
final class JourneyPlanRanking {

    /** 单次查询最多返回的候选条数。 */
    static final int MAX_PLANS = 3;

    private static final String MODE_CAMPUS_WALK = "CAMPUS_WALK";
    private static final String MODE_WALK = "WALK";

    private JourneyPlanRanking() {
    }

    /**
     * 合并去重、按偏好排序并截取前 {@value #MAX_PLANS} 条。
     *
     * @param candidates 本次查询实际获得的候选（可为 {@code null} 或含 {@code null} 元素）
     * @param preference 请求偏好；{@code null} 按推荐处理
     * @return 去重排序后的候选，最多 {@value #MAX_PLANS} 条；无候选返回空列表
     */
    static List<JourneyPlan> arrange(List<JourneyPlan> candidates, TransitPreference preference) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Comparator<JourneyPlan> order = orderFor(preference);
        Map<String, JourneyPlan> deduplicated = new LinkedHashMap<>();
        for (JourneyPlan candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            deduplicated.merge(dedupSignature(candidate), candidate, (kept, next) ->
                    order.compare(next, kept) < 0 ? next : kept);
        }
        List<JourneyPlan> ordered = new ArrayList<>(deduplicated.values());
        ordered.sort(order);
        if (ordered.size() > MAX_PLANS) {
            ordered = new ArrayList<>(ordered.subList(0, MAX_PLANS));
        }
        return List.copyOf(ordered);
    }

    /** 指定偏好的候选比较器，最后一维为稳定 {@code planId}。 */
    static Comparator<JourneyPlan> orderFor(TransitPreference preference) {
        TransitPreference effective = preference == null ? TransitPreference.RECOMMENDED : preference;
        return switch (effective) {
            case LESS_WALKING -> order(byWalking(), byTransfers(), byDuration());
            case FEWER_TRANSFERS -> order(byTransfers(), byWalking(), byDuration());
            case RECOMMENDED -> order(byDuration(), byTransfers(), byWalking());
        };
    }

    /**
     * 方案的去重签名：实际出校点身份 + 按顺序排列的乘车段（线路、方向、上下车站）。
     * 步行段（校园步行与城市步行）不参与签名。
     */
    static String dedupSignature(JourneyPlan plan) {
        return routeSignature(plan.gateExitPoint(), plan.segments());
    }

    /**
     * 路线身份：与提供方返回顺序无关，由实际出校点与乘车序列决定。planId 用它生成，
     * 因此同一条路线即使提供方交换返回顺序、或同指标候选先后变化，身份仍保持稳定。
     */
    static String routeSignature(JourneyPoint gateExitPoint, List<JourneySegment> segments) {
        StringBuilder signature = new StringBuilder(128);
        signature.append("exit=").append(exitIdentity(gateExitPoint));
        int rideIndex = 0;
        if (segments != null) {
            for (JourneySegment segment : segments) {
                if (segment == null || isWalkMode(segment.mode())) {
                    continue;
                }
                rideIndex++;
                signature.append("|ride").append(rideIndex)
                        .append(":mode=").append(normalizedMode(segment.mode()))
                        .append(",line=").append(lineIdentity(segment.lineId(), segment.lineName()))
                        .append(",direction=").append(text(segment.direction()))
                        .append(",boarding=").append(text(segment.boardingStation()))
                        .append(",alighting=").append(text(segment.alightingStation()));
            }
        }
        return signature.toString();
    }

    private static Comparator<JourneyPlan> order(Comparator<JourneyPlan> first,
                                                 Comparator<JourneyPlan> second,
                                                 Comparator<JourneyPlan> third) {
        return first.thenComparing(second)
                .thenComparing(third)
                .thenComparing(JourneyPlan::planId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<JourneyPlan> byWalking() {
        return Comparator.comparing(JourneyPlan::totalWalkingMeters,
                Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<JourneyPlan> byTransfers() {
        return Comparator.comparing(JourneyPlan::transferCount,
                Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<JourneyPlan> byDuration() {
        return Comparator.comparing(JourneyPlan::totalDurationSeconds,
                Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static String exitIdentity(JourneyPoint exitPoint) {
        if (exitPoint == null) {
            return "none";
        }
        String pointId = text(exitPoint.pointId());
        if (!pointId.isEmpty()) {
            return "point:" + pointId;
        }
        return "coord:" + text(exitPoint.coordinateSystem()) + ','
                + number(exitPoint.longitude()) + ',' + number(exitPoint.latitude());
    }

    private static String lineIdentity(String lineId, String lineName) {
        String id = text(lineId);
        if (!id.isEmpty()) {
            return "id:" + id;
        }
        String name = text(lineName);
        return name.isEmpty() ? "none" : "name:" + name;
    }

    private static String normalizedMode(String mode) {
        String value = text(mode);
        return value.isEmpty() ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static boolean isWalkMode(String mode) {
        String value = normalizedMode(mode);
        return MODE_WALK.equals(value) || MODE_CAMPUS_WALK.equals(value);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String number(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
