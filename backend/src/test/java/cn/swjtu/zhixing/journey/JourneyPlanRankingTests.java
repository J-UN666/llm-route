package cn.swjtu.zhixing.journey;

import static org.assertj.core.api.Assertions.assertThat;

import cn.swjtu.zhixing.transit.TransitPreference;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 候选整理单元验收（W1-09 验收 A/B/C）：排序规则、去重签名、未知指标、数量上限与顺序稳定性。
 * 全部使用构造的 {@link JourneyPlan}，不依赖真实数据与城市接口。
 */
class JourneyPlanRankingTests {

    private static final String EXIT_EAST = "osm_node_13469658219";
    private static final String EXIT_WEST = "osm_node_west_exit";
    private static final OffsetDateTime DEPARTURE =
            OffsetDateTime.parse("2026-11-16T09:00:00+08:00");

    // ---------- 验收 A：偏好排序 ----------

    @Test
    void lessWalkingPutsShorterWalkingFirstAndFewerTransfersPutsFewerTransfersFirst() {
        // A：步行 300 米、换乘 2 次、总耗时 2200 秒；B：步行 600 米、换乘 1 次、总耗时 1900 秒。
        JourneyPlan a = plan("plan_a", EXIT_EAST, 2200, "300", 2,
                metro("line_2", "往龙泉驿方向", "犀浦", "天府广场"),
                bus("bus_56", "往茶店子公交站方向", "犀浦", "天府广场"));
        JourneyPlan b = plan("plan_b", EXIT_EAST, 1900, "600", 1,
                bus("bus_119", "往火车北站方向", "犀浦", "天府广场"),
                metro("line_7", "往十里店方向", "西南交大", "天府广场"));

        assertThat(ids(JourneyPlanRanking.arrange(List.of(a, b), TransitPreference.LESS_WALKING)))
                .containsExactly("plan_a", "plan_b");
        assertThat(ids(JourneyPlanRanking.arrange(List.of(a, b), TransitPreference.FEWER_TRANSFERS)))
                .containsExactly("plan_b", "plan_a");
        // 推荐在两项已知指标不同时按总耗时优先，B 更快。
        assertThat(ids(JourneyPlanRanking.arrange(List.of(a, b), TransitPreference.RECOMMENDED)))
                .containsExactly("plan_b", "plan_a");
    }

    @Test
    void preferenceComparisonOrderFollowsDocumentedDimensions() {
        JourneyPlan fastLongWalk = plan("plan_fast", EXIT_EAST, 1000, "900", 2, ridesA());
        JourneyPlan mediumBalanced = plan("plan_mid", EXIT_EAST, 1200, "600", 1, ridesB());
        JourneyPlan slowShortWalk = plan("plan_slow", EXIT_EAST, 1400, "300", 1, ridesC());

        assertThat(ids(JourneyPlanRanking.arrange(
                List.of(fastLongWalk, mediumBalanced, slowShortWalk), TransitPreference.RECOMMENDED)))
                .containsExactly("plan_fast", "plan_mid", "plan_slow");
        assertThat(ids(JourneyPlanRanking.arrange(
                List.of(fastLongWalk, mediumBalanced, slowShortWalk), TransitPreference.LESS_WALKING)))
                .containsExactly("plan_slow", "plan_mid", "plan_fast");
        // 换乘少者优先：两个 1 换乘方案按步行距离排序（慢方案 300 米在前），2 换乘方案最后。
        assertThat(ids(JourneyPlanRanking.arrange(
                List.of(fastLongWalk, mediumBalanced, slowShortWalk), TransitPreference.FEWER_TRANSFERS)))
                .containsExactly("plan_slow", "plan_mid", "plan_fast");
    }

    // ---------- 验收 B：去重签名 ----------

    @Test
    void duplicateRideSequenceKeepsPreferredCandidateAndItsStablePlanId() {
        JourneyPlan betterWalking = plan("plan_walk", EXIT_EAST, 2500, "300", 2, ridesA());
        JourneyPlan betterTransfer = plan("plan_transfer", EXIT_EAST, 1800, "600", 1, ridesA());

        assertThat(ids(JourneyPlanRanking.arrange(
                List.of(betterWalking, betterTransfer), TransitPreference.LESS_WALKING)))
                .containsExactly("plan_walk");
        assertThat(ids(JourneyPlanRanking.arrange(
                List.of(betterWalking, betterTransfer), TransitPreference.FEWER_TRANSFERS)))
                .containsExactly("plan_transfer");
        assertThat(ids(JourneyPlanRanking.arrange(
                List.of(betterWalking, betterTransfer), TransitPreference.RECOMMENDED)))
                .containsExactly("plan_transfer");
    }

    @Test
    void equalDuplicateMetricsKeepSmallerPlanIdRegardlessOfInputOrder() {
        JourneyPlan zz = plan("plan_zz", EXIT_EAST, 1500, "300", 1, ridesA());
        JourneyPlan aa = plan("plan_aa", EXIT_EAST, 1500, "300", 1, ridesA());

        assertThat(ids(JourneyPlanRanking.arrange(List.of(zz, aa), TransitPreference.RECOMMENDED)))
                .containsExactly("plan_aa");
        assertThat(ids(JourneyPlanRanking.arrange(List.of(aa, zz), TransitPreference.RECOMMENDED)))
                .containsExactly("plan_aa");
    }

    @Test
    void walkSegmentsDoNotEnterDuplicateSignature() {
        JourneyPlan direct = plan("plan_direct", EXIT_EAST, 1600, "500", 1,
                metro("line_2", "往龙泉驿方向", "犀浦", "天府广场"),
                bus("bus_56", "往茶店子公交站方向", "犀浦", "天府广场"));
        JourneyPlan extraWalk = plan("plan_extra_walk", EXIT_EAST, 1600, "500", 1,
                metro("line_2", "往龙泉驿方向", "犀浦", "天府广场"),
                walk(),
                bus("bus_56", "往茶店子公交站方向", "犀浦", "天府广场"));

        assertThat(JourneyPlanRanking.dedupSignature(direct))
                .isEqualTo(JourneyPlanRanking.dedupSignature(extraWalk));
        assertThat(JourneyPlanRanking.arrange(List.of(direct, extraWalk),
                TransitPreference.RECOMMENDED)).hasSize(1);
    }

    @Test
    void differentGateExitPointsRemainDistinct() {
        JourneyPlan east = plan("plan_east", EXIT_EAST, 1600, "500", 1, ridesA());
        JourneyPlan west = plan("plan_west", EXIT_WEST, 1600, "500", 1, ridesA());

        assertThat(ids(JourneyPlanRanking.arrange(List.of(east, west),
                TransitPreference.RECOMMENDED))).containsExactly("plan_east", "plan_west");
    }

    @Test
    void differentBoardingOrAlightingStationsRemainDistinct() {
        JourneyPlan base = plan("plan_base", EXIT_EAST, 1600, "500", 1,
                metro("line_2", "往龙泉驿方向", "犀浦", "天府广场"));
        JourneyPlan otherBoarding = plan("plan_other_boarding", EXIT_EAST, 1600, "500", 1,
                metro("line_2", "往龙泉驿方向", "天河路", "天府广场"));
        JourneyPlan otherAlighting = plan("plan_other_alighting", EXIT_EAST, 1600, "500", 1,
                metro("line_2", "往龙泉驿方向", "犀浦", "春熙路"));
        JourneyPlan otherDirection = plan("plan_other_direction", EXIT_EAST, 1600, "500", 1,
                metro("line_2", "往犀浦方向", "犀浦", "天府广场"));

        assertThat(JourneyPlanRanking.arrange(
                List.of(base, otherBoarding), TransitPreference.RECOMMENDED)).hasSize(2);
        assertThat(JourneyPlanRanking.arrange(
                List.of(base, otherAlighting), TransitPreference.RECOMMENDED)).hasSize(2);
        assertThat(JourneyPlanRanking.arrange(
                List.of(base, otherDirection), TransitPreference.RECOMMENDED)).hasSize(2);
    }

    @Test
    void differentRideOrderRemainsDistinct() {
        JourneySegment metro = metro("line_2", "往龙泉驿方向", "犀浦", "天府广场");
        JourneySegment bus = bus("bus_56", "往茶店子公交站方向", "犀浦", "天府广场");
        JourneyPlan metroThenBus = plan("plan_metro_bus", EXIT_EAST, 1600, "500", 1, metro, bus);
        JourneyPlan busThenMetro = plan("plan_bus_metro", EXIT_EAST, 1600, "500", 1, bus, metro);

        assertThat(JourneyPlanRanking.arrange(List.of(metroThenBus, busThenMetro),
                TransitPreference.RECOMMENDED)).hasSize(2);
    }

    // ---------- 验收 C：未知指标、数量与稳定性 ----------

    @Test
    void unknownMetricsSortAfterKnownValuesWithinEachDimension() {
        JourneyPlan known = plan("plan_known", EXIT_EAST, 1000, "500", 1, ridesA());
        JourneyPlan nullWalking = plan("plan_null_walking", EXIT_EAST, 900, null, 0, ridesB());
        JourneyPlan nullTransfer = plan("plan_null_transfer", EXIT_EAST, 800, "400", null, ridesC());
        JourneyPlan nullDuration = plan("plan_null_duration", EXIT_EAST, null, "200", 0, ridesD());
        List<JourneyPlan> candidates = List.of(known, nullWalking, nullTransfer, nullDuration);

        assertThat(ids(sorted(candidates, TransitPreference.LESS_WALKING)))
                .containsExactly("plan_null_duration", "plan_null_transfer", "plan_known",
                        "plan_null_walking");
        assertThat(ids(sorted(candidates, TransitPreference.FEWER_TRANSFERS)))
                .containsExactly("plan_null_duration", "plan_null_walking", "plan_known",
                        "plan_null_transfer");
        assertThat(ids(sorted(candidates, TransitPreference.RECOMMENDED)))
                .containsExactly("plan_null_transfer", "plan_null_walking", "plan_known",
                        "plan_null_duration");
    }

    @Test
    void unknownMetricsStayNullInReturnedCandidates() {
        JourneyPlan unknownWalking = plan("plan_null_walking", EXIT_EAST, 900, null, 0, ridesA());
        JourneyPlan knownWalking = plan("plan_known", EXIT_EAST, 1000, "500", 1, ridesB());

        List<JourneyPlan> arranged = JourneyPlanRanking.arrange(
                List.of(unknownWalking, knownWalking), TransitPreference.LESS_WALKING);

        assertThat(arranged).hasSize(2);
        assertThat(arranged.get(0).planId()).isEqualTo("plan_known");
        assertThat(arranged.get(1).totalWalkingMeters()).isNull();
        assertThat(arranged.get(0).totalWalkingMeters()).isEqualByComparingTo("500");
    }

    @Test
    void emptyAndSingleCandidateCountsAreCorrect() {
        assertThat(JourneyPlanRanking.arrange(null, TransitPreference.RECOMMENDED)).isEmpty();
        assertThat(JourneyPlanRanking.arrange(List.of(), TransitPreference.RECOMMENDED)).isEmpty();
        JourneyPlan only = plan("plan_only", EXIT_EAST, 1000, "300", 1, ridesA());
        assertThat(ids(JourneyPlanRanking.arrange(List.of(only), TransitPreference.RECOMMENDED)))
                .containsExactly("plan_only");
    }

    @Test
    void fourDistinctCandidatesReturnOnlyTopThree() {
        List<JourneyPlan> candidates = List.of(
                plan("plan_4000", EXIT_EAST, 4000, "300", 1, ridesA()),
                plan("plan_1000", EXIT_EAST, 1000, "300", 1, ridesB()),
                plan("plan_3000", EXIT_EAST, 3000, "300", 1, ridesC()),
                plan("plan_2000", EXIT_EAST, 2000, "300", 1, ridesD()));

        assertThat(ids(JourneyPlanRanking.arrange(candidates, TransitPreference.RECOMMENDED)))
                .containsExactly("plan_1000", "plan_2000", "plan_3000");
    }

    @Test
    void deduplicateBeforeApplyingTheThreePlanLimit() {
        // 前两条为同乘车序列的重复项；先去重后共 4 个不同方案，再取前三条。
        JourneyPlan duplicateBetter = plan("plan_dup_better", EXIT_EAST, 1000, "300", 1, ridesA());
        JourneyPlan duplicateWorse = plan("plan_dup_worse", EXIT_EAST, 1100, "600", 1, ridesA());
        JourneyPlan other1 = plan("plan_other_1", EXIT_EAST, 1200, "500", 1, ridesB());
        JourneyPlan other2 = plan("plan_other_2", EXIT_EAST, 1300, "500", 1, ridesC());
        JourneyPlan other3 = plan("plan_other_3", EXIT_EAST, 1400, "500", 1, ridesD());

        assertThat(ids(JourneyPlanRanking.arrange(
                List.of(duplicateBetter, duplicateWorse, other1, other2, other3),
                TransitPreference.RECOMMENDED)))
                .containsExactly("plan_dup_better", "plan_other_1", "plan_other_2");
    }

    @Test
    void equalMetricsAreOrderedByPlanIdStably() {
        JourneyPlan c = plan("plan_c", EXIT_EAST, 1000, "300", 1, ridesA());
        JourneyPlan a = plan("plan_a", EXIT_EAST, 1000, "300", 1, ridesB());
        JourneyPlan b = plan("plan_b", EXIT_EAST, 1000, "300", 1, ridesC());

        List<String> first = ids(JourneyPlanRanking.arrange(List.of(c, a, b),
                TransitPreference.RECOMMENDED));
        List<String> second = ids(JourneyPlanRanking.arrange(List.of(b, c, a),
                TransitPreference.RECOMMENDED));

        assertThat(first).containsExactly("plan_a", "plan_b", "plan_c");
        assertThat(second).isEqualTo(first);
    }

    // ---------- helpers ----------

    private static List<JourneyPlan> sorted(List<JourneyPlan> candidates,
                                            TransitPreference preference) {
        List<JourneyPlan> copy = new ArrayList<>(candidates);
        copy.sort(JourneyPlanRanking.orderFor(preference));
        return copy;
    }

    private static List<String> ids(List<JourneyPlan> plans) {
        return plans.stream().map(JourneyPlan::planId).toList();
    }

    private static JourneySegment[] ridesA() {
        return new JourneySegment[]{
                metro("line_2", "往龙泉驿方向", "犀浦", "天府广场"),
                bus("bus_56", "往茶店子公交站方向", "犀浦", "天府广场")};
    }

    private static JourneySegment[] ridesB() {
        return new JourneySegment[]{
                bus("bus_119", "往火车北站方向", "犀浦", "天府广场"),
                metro("line_7", "往十里店方向", "西南交大", "天府广场")};
    }

    private static JourneySegment[] ridesC() {
        return new JourneySegment[]{
                metro("line_6", "往望丛祠方向", "犀浦", "天府广场")};
    }

    private static JourneySegment[] ridesD() {
        return new JourneySegment[]{
                bus("bus_311", "往天府广场方向", "犀浦", "天府广场")};
    }

    private static JourneySegment metro(String lineId, String direction, String boarding,
                                        String alighting) {
        return ride("METRO", lineId, direction, boarding, alighting);
    }

    private static JourneySegment bus(String lineId, String direction, String boarding,
                                      String alighting) {
        return ride("BUS", lineId, direction, boarding, alighting);
    }

    private static JourneySegment ride(String mode, String lineId, String direction,
                                       String boarding, String alighting) {
        return new JourneySegment(2, mode, "乘车", null, null, lineId, null, direction,
                boarding, alighting, null, null, null, null);
    }

    private static JourneySegment walk() {
        return new JourneySegment(3, "WALK", "步行换乘", null, null, null, null, null, null,
                null, 200.0, 180, null, null);
    }

    /**
     * 构造一条候选：校园步行段 + 给定城市分段；总耗时与总步行量直接取参数（便于精确控制排序维度），
     * 未知指标传 {@code null}。
     */
    private static JourneyPlan plan(String planId, String exitPointId, Integer totalDurationSeconds,
                                    String totalWalkingMeters, Integer transferCount,
                                    JourneySegment... citySegments) {
        List<JourneySegment> segments = new ArrayList<>();
        segments.add(new JourneySegment(1, "CAMPUS_WALK", "校园步行至出校点", null, null, null,
                null, null, null, null, 800.0, 600, null, null));
        segments.addAll(List.of(citySegments));
        BigDecimal walking = totalWalkingMeters == null ? null : new BigDecimal(totalWalkingMeters);
        return new JourneyPlan(
                planId,
                "osm_way_1098786977",
                "C01",
                "campus_gate_east",
                exitPoint(exitPointId),
                DEPARTURE,
                DEPARTURE.plusSeconds(600),
                DEPARTURE.plusSeconds(600),
                0,
                totalDurationSeconds == null ? null : DEPARTURE.plusSeconds(totalDurationSeconds),
                totalDurationSeconds,
                walking,
                transferCount,
                new BigDecimal("5.00"),
                segments,
                List.of("AMAP_V3_TRANSIT_INTEGRATED"),
                DEPARTURE,
                "places=2026-11-15.2;connectors=2026-11-15.4", null);
    }

    private static JourneyPoint exitPoint(String pointId) {
        return new JourneyPoint(pointId, "出校点", 103.9921, 30.7559, "WGS84", "gate_exit_point");
    }
}
