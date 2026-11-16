package cn.swjtu.zhixing.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.swjtu.zhixing.campus.CampusConnector;
import cn.swjtu.zhixing.campus.CampusConnectorGeometry;
import cn.swjtu.zhixing.campus.CampusConnectorPoint;
import cn.swjtu.zhixing.campus.CampusConnectorSegment;
import cn.swjtu.zhixing.campus.CampusConnectorService;
import cn.swjtu.zhixing.campus.CampusConnectorUnavailability;
import cn.swjtu.zhixing.campus.GeoPosition;
import cn.swjtu.zhixing.campus.PlaceCandidate;
import cn.swjtu.zhixing.campus.PlaceKind;
import cn.swjtu.zhixing.campus.PlaceNotFoundException;
import cn.swjtu.zhixing.campus.PlaceService;
import cn.swjtu.zhixing.transit.TransitErrorCode;
import cn.swjtu.zhixing.transit.TransitLine;
import cn.swjtu.zhixing.transit.TransitPlan;
import cn.swjtu.zhixing.transit.TransitPoint;
import cn.swjtu.zhixing.transit.TransitPreference;
import cn.swjtu.zhixing.transit.TransitProvider;
import cn.swjtu.zhixing.transit.TransitSearchResult;
import cn.swjtu.zhixing.transit.TransitSegment;
import cn.swjtu.zhixing.transit.TransitStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * 组合逻辑验收（W1-08 验收 A/B/C）：用受控的校园接驳与城市段桩验证时间推进、总量合计、
 * 费用/跨日/无接驳/上游失败/部分候选失败与 planId 稳定性。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JourneyServiceTests {

    private static final String ORIGIN = "osm_way_1098786977";
    private static final String DESTINATION = "C01";
    private static final String DEPARTURE = "2026-11-16T09:00:00+08:00";
    private static final String EXIT_POINT = "osm_node_13469658219";
    private static final String ORIGIN_POINT = "osm_node_5167087319";
    private static final OffsetDateTime QUERIED_AT = OffsetDateTime.parse("2026-11-16T01:00:00Z");

    @Mock
    private PlaceService placeService;

    @Mock
    private CampusConnectorService connectorService;

    @Mock
    private TransitProvider transitProvider;

    private JourneyService service;

    @BeforeEach
    void setUp() {
        service = new JourneyService(placeService, connectorService, transitProvider,
                Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneOffset.UTC));
        when(placeService.dataVersion()).thenReturn("2026-11-15.2");
        when(connectorService.dataVersion()).thenReturn("2026-11-15.4");
        when(placeService.getById(ORIGIN)).thenReturn(campusPlace(ORIGIN, "西南交通大学犀浦校区图书馆"));
        when(placeService.getById(DESTINATION)).thenReturn(cityPlace(DESTINATION, "天府广场"));
    }

    @Test
    void campusDeparturePlusConnectorComposesCitySegmentAndTotals() {
        CampusConnector connector = connector("gate_east", "东门", 800.0, 600, EXIT_POINT);
        givenConnectors(connector);
        givenProviderSuccess(plan(1500, 250, 1, new BigDecimal("5.00")));

        JourneyResponse response = service.plan(request(DEPARTURE));

        assertThat(response.status()).isEqualTo(JourneyStatus.SUCCEEDED);
        assertThat(response.requestId()).startsWith("jr-");
        assertThat(response.provider()).isEqualTo("AMAP");
        assertThat(response.campusDataVersion()).isEqualTo("places=2026-11-15.2;connectors=2026-11-15.4");
        assertThat(response.plans()).hasSize(1);
        JourneyPlan plan = response.plans().get(0);
        assertThat(plan.departureTime()).isEqualTo(OffsetDateTime.parse("2026-11-16T09:00:00+08:00"));
        assertThat(plan.cityDepartureTime()).isEqualTo(OffsetDateTime.parse("2026-11-16T09:10:00+08:00"));
        assertThat(plan.providerDepartureTime()).isEqualTo(OffsetDateTime.parse("2026-11-16T09:10:00+08:00"));
        assertThat(plan.arrivalTime()).isEqualTo(OffsetDateTime.parse("2026-11-16T09:35:00+08:00"));
        assertThat(plan.totalDurationSeconds()).isEqualTo(2100);
        assertThat(plan.totalWalkingMeters()).isEqualByComparingTo("1050");
        assertThat(plan.transferCount()).isEqualTo(1);
        assertThat(plan.costYuan()).isEqualByComparingTo("5.00");
        assertThat(plan.roundingWaitSeconds()).isZero();
        assertThat(plan.segments()).hasSize(3);
        assertThat(plan.segments().get(0).mode()).isEqualTo("CAMPUS_WALK");
        assertThat(plan.segments().get(0).sequence()).isEqualTo(1);
        assertThat(plan.segments().get(0).distanceMeters()).isEqualTo(800.0);
        assertThat(plan.segments().get(0).durationSeconds()).isEqualTo(600);
        assertThat(plan.segments().get(0).geometry().coordinateSystem()).isEqualTo("WGS84");
        assertThat(plan.segments().get(1).mode()).isEqualTo("WALK");
        assertThat(plan.segments().get(2).mode()).isEqualTo("METRO");

        ArgumentCaptor<TransitPoint> originCaptor = ArgumentCaptor.forClass(TransitPoint.class);
        ArgumentCaptor<OffsetDateTime> departureCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(transitProvider).search(originCaptor.capture(), any(TransitPoint.class),
                departureCaptor.capture(), eq(TransitPreference.RECOMMENDED));
        assertThat(originCaptor.getValue().pointId()).isEqualTo(EXIT_POINT);
        assertThat(departureCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-11-16T09:10:00+08:00"));
    }

    @Test
    void connectorDurationWithSecondsAdvancesProviderClockAndRoundingWait() {
        CampusConnector connector = connector("gate_east", "东门", 840.0, 630, EXIT_POINT);
        givenConnectors(connector);
        givenProviderAnswer(requested -> new TransitSearchResult("AMAP", QUERIED_AT, requested,
                requested.plusSeconds(30), 30, TransitStatus.OK, List.of(plan(1500, 250, 1, new BigDecimal("5.00")))));

        JourneyPlan plan = service.plan(request(DEPARTURE)).plans().get(0);

        assertThat(plan.cityDepartureTime()).isEqualTo(OffsetDateTime.parse("2026-11-16T09:10:30+08:00"));
        assertThat(plan.providerDepartureTime()).isEqualTo(OffsetDateTime.parse("2026-11-16T09:11:00+08:00"));
        assertThat(plan.roundingWaitSeconds()).isEqualTo(30);
        assertThat(plan.arrivalTime()).isEqualTo(OffsetDateTime.parse("2026-11-16T09:36:00+08:00"));
        assertThat(plan.totalDurationSeconds()).isEqualTo(2160);
    }

    @Test
    void missingCostAndWalkingMetricsStayNull() {
        givenConnectors(connector("gate_east", "东门", 800.0, 600, EXIT_POINT));
        givenProviderSuccess(plan(1500, null, null, null));

        JourneyPlan plan = service.plan(request(DEPARTURE)).plans().get(0);

        assertThat(plan.costYuan()).isNull();
        assertThat(plan.totalWalkingMeters()).isNull();
        assertThat(plan.transferCount()).isNull();
    }

    @Test
    void crossMidnightQueryKeepsAbsoluteDurationsAndNextDayArrival() {
        givenConnectors(connector("gate_east", "东门", 800.0, 600, EXIT_POINT));
        givenProviderSuccess(plan(1500, 250, 1, new BigDecimal("5.00")));

        JourneyPlan plan = service.plan(request("2026-11-16T23:50:00+08:00")).plans().get(0);

        assertThat(plan.cityDepartureTime()).isEqualTo(OffsetDateTime.parse("2026-11-17T00:00:00+08:00"));
        assertThat(plan.arrivalTime()).isEqualTo(OffsetDateTime.parse("2026-11-17T00:25:00+08:00"));
        assertThat(plan.totalDurationSeconds()).isEqualTo(2100);
    }

    @Test
    void noAvailableConnectorFailsBeforeCallingProvider() {
        when(connectorService.resolve(eq(ORIGIN), any(OffsetDateTime.class)))
                .thenReturn(new CampusConnectorService.Resolution(ORIGIN, "CAMPUS", "2026-11-15.4",
                        List.of(),
                        List.of(new CampusConnectorUnavailability("connector_library_west_gate",
                                "gate_west", "PENDING_VERIFICATION", "CONNECTOR_NOT_VERIFIED",
                                "接驳尚未通过核实"))));

        JourneyApiException error = expectError(DEPARTURE, HttpStatus.UNPROCESSABLE_ENTITY,
                "CAMPUS_CONNECTOR_UNAVAILABLE", false);

        assertThat(error.getMessage()).contains("CONNECTOR_NOT_VERIFIED");
        verifyNoInteractions(transitProvider);
    }

    @Test
    void allProviderFailuresMapToGatewayErrorWithRetryableFlag() {
        givenConnectors(connector("gate_east", "东门", 800.0, 600, EXIT_POINT));
        givenProviderError(TransitErrorCode.PROVIDER_ERROR);

        JourneyApiException error = expectError(DEPARTURE, HttpStatus.BAD_GATEWAY,
                "PROVIDER_ERROR", true);

        assertThat(error.getMessage()).contains("所有校门出校点");
    }

    @Test
    void providerTimeoutMapsToGatewayTimeout() {
        givenConnectors(connector("gate_east", "东门", 800.0, 600, EXIT_POINT));
        givenProviderError(TransitErrorCode.NETWORK_TIMEOUT);

        expectError(DEPARTURE, HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT", true);
    }

    @Test
    void partialFailureKeepsSuccessfulGateAndWarnsAboutFailedGate() {
        CampusConnector east = connector("gate_east", "东门", 800.0, 600, EXIT_POINT);
        CampusConnector west = connector("gate_west", "西门", 900.0, 700, "osm_node_west_exit");
        givenConnectors(east, west);
        when(transitProvider.search(any(TransitPoint.class), any(TransitPoint.class),
                any(OffsetDateTime.class), any(TransitPreference.class)))
                .thenAnswer(invocation -> {
                    TransitPoint origin = invocation.getArgument(0);
                    OffsetDateTime requested = invocation.getArgument(2);
                    if ("osm_node_west_exit".equals(origin.pointId())) {
                        return new TransitSearchResult("AMAP", QUERIED_AT, requested, requested, 0,
                                TransitStatus.NO_ROUTE, List.of(), TransitErrorCode.PROVIDER_ERROR,
                                "上游失败");
                    }
                    return new TransitSearchResult("AMAP", QUERIED_AT, requested, requested, 0,
                            TransitStatus.OK, List.of(plan(1500, 250, 1, new BigDecimal("5.00"))));
                });

        JourneyResponse response = service.plan(request(DEPARTURE));

        assertThat(response.status()).isEqualTo(JourneyStatus.SUCCEEDED);
        assertThat(response.plans()).hasSize(1);
        assertThat(response.plans().get(0).gateId()).isEqualTo("gate_east");
        assertThat(response.warnings()).hasSize(1);
        assertThat(response.warnings().get(0)).contains("gate_west").contains("PROVIDER_ERROR");
        verify(transitProvider, org.mockito.Mockito.times(2))
                .search(any(TransitPoint.class), any(TransitPoint.class),
                        any(OffsetDateTime.class), any(TransitPreference.class));
    }

    @Test
    void providerNoRouteReturnsEmptyNoRouteResponse() {
        givenConnectors(connector("gate_east", "东门", 800.0, 600, EXIT_POINT));
        givenProviderAnswer(requested -> new TransitSearchResult("AMAP", QUERIED_AT, requested,
                requested, 0, TransitStatus.NO_ROUTE, List.of()));

        JourneyResponse response = service.plan(request(DEPARTURE));

        assertThat(response.status()).isEqualTo(JourneyStatus.NO_ROUTE);
        assertThat(response.plans()).isEmpty();
        assertThat(response.requestId()).startsWith("jr-");
    }

    @Test
    void planIdIsStableAcrossRepeatedIdenticalQueries() {
        givenConnectors(connector("gate_east", "东门", 800.0, 600, EXIT_POINT));
        givenProviderSuccess(plan(1500, 250, 1, new BigDecimal("5.00")));

        String first = service.plan(request(DEPARTURE)).plans().get(0).planId();
        String second = service.plan(request(DEPARTURE)).plans().get(0).planId();

        assertThat(first).matches("plan_[0-9a-f]{16}");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void preferenceRankingAppliesAcrossAllAvailableGateCandidates() {
        CampusConnector east = connector("gate_east", "东门", 800.0, 600, EXIT_POINT);
        CampusConnector west = connector("gate_west", "西门", 900.0, 700, "osm_node_west_exit");
        givenConnectors(east, west);
        when(transitProvider.search(any(TransitPoint.class), any(TransitPoint.class),
                any(OffsetDateTime.class), any(TransitPreference.class)))
                .thenAnswer(invocation -> {
                    TransitPoint origin = invocation.getArgument(0);
                    OffsetDateTime requested = invocation.getArgument(2);
                    List<TransitPlan> plans;
                    if ("osm_node_west_exit".equals(origin.pointId())) {
                        plans = List.of(planVia("line_7", "往十里店方向", "西南交大", "天府广场",
                                900, 900, 1));
                    } else {
                        plans = List.of(
                                planVia("line_2", "往龙泉驿方向", "犀浦", "天府广场",
                                        1600, 700, 1),
                                planVia("line_6", "往望丛祠方向", "犀浦", "天府广场",
                                        1000, 300, 2));
                    }
                    return new TransitSearchResult("AMAP", QUERIED_AT, requested, requested, 0,
                            TransitStatus.OK, plans);
                });

        List<JourneyPlan> recommended = service.plan(request(DEPARTURE)).plans();
        assertThat(recommended).hasSize(3);
        assertThat(gateIds(recommended)).containsExactly("gate_west", "gate_east", "gate_east");
        assertThat(totalDurations(recommended)).containsExactly(1600, 1600, 2200);

        List<JourneyPlan> lessWalking = service.plan(requestWithPreference("LESS_WALKING")).plans();
        assertThat(lessWalking).hasSize(3);
        assertThat(gateIds(lessWalking)).containsExactly("gate_east", "gate_east", "gate_west");
        assertThat(totalWalking(lessWalking)).containsExactly(1100, 1500, 1800);

        List<JourneyPlan> fewerTransfers =
                service.plan(requestWithPreference("FEWER_TRANSFERS")).plans();
        assertThat(fewerTransfers).hasSize(3);
        assertThat(gateIds(fewerTransfers)).containsExactly("gate_east", "gate_west", "gate_east");
        assertThat(totalDurations(fewerTransfers)).containsExactly(2200, 1600, 1600);
        assertThat(transferCounts(fewerTransfers)).containsExactly(1, 1, 2);

        verify(transitProvider, org.mockito.Mockito.times(2))
                .search(any(TransitPoint.class), any(TransitPoint.class),
                        any(OffsetDateTime.class), eq(TransitPreference.LESS_WALKING));
        verify(transitProvider, org.mockito.Mockito.times(2))
                .search(any(TransitPoint.class), any(TransitPoint.class),
                        any(OffsetDateTime.class), eq(TransitPreference.FEWER_TRANSFERS));
    }

    @Test
    void successResponseDeduplicatesPreferredCandidateAndLimitsToThreePlans() {
        givenConnectors(connector("gate_east", "东门", 800.0, 600, EXIT_POINT));
        givenProviderAnswer(requested -> new TransitSearchResult("AMAP", QUERIED_AT, requested,
                requested, 0, TransitStatus.OK, List.of(
                // 前两条为同一乘车序列的重复项：推荐偏好保留耗时更短的，少步行保留步行更少的。
                planVia("line_2", "往龙泉驿方向", "犀浦", "天府广场", 1200, 250, 1),
                planVia("line_2", "往龙泉驿方向", "犀浦", "天府广场", 1100, 400, 1),
                planVia("line_6", "往望丛祠方向", "犀浦", "天府广场", 1300, 300, 2),
                planVia("line_7", "往十里店方向", "西南交大", "天府广场", 1400, 350, 0))));

        List<JourneyPlan> recommended = service.plan(request(DEPARTURE)).plans();
        assertThat(recommended).hasSize(JourneyPlanRanking.MAX_PLANS);
        assertThat(lineIds(recommended)).containsExactly("line_2", "line_6", "line_7");
        assertThat(totalDurations(recommended)).containsExactly(1700, 1900, 2000);
        assertThat(totalWalking(recommended)).containsExactly(1200, 1100, 1150);

        // 同查询重复执行，排序与 planId 稳定。
        assertThat(ids(service.plan(request(DEPARTURE)).plans()))
                .isEqualTo(ids(recommended));

        List<JourneyPlan> lessWalking = service.plan(requestWithPreference("LESS_WALKING")).plans();
        assertThat(lessWalking).hasSize(JourneyPlanRanking.MAX_PLANS);
        // 同一乘车序列的重复项共享路线身份与 planId：切换偏好只影响保留哪一条及其指标。
        assertThat(ids(lessWalking)).isEqualTo(ids(recommended));
        assertThat(totalWalking(lessWalking)).containsExactly(1050, 1100, 1150);
        assertThat(totalDurations(lessWalking)).containsExactly(1800, 1900, 2000);
    }

    @Test
    void planIdsFollowRouteIdentityWhenProviderReordersCandidates() {
        givenConnectors(connector("gate_east", "东门", 800.0, 600, EXIT_POINT));
        AtomicInteger calls = new AtomicInteger();
        givenProviderAnswer(requested -> {
            List<TransitPlan> plans = List.of(
                    planVia("line_2", "往龙泉驿方向", "犀浦", "天府广场", 1200, 250, 1),
                    planVia("line_6", "往望丛祠方向", "犀浦", "天府广场", 1300, 300, 2));
            if (calls.getAndIncrement() % 2 == 1) {
                plans = plans.reversed();
            }
            return new TransitSearchResult("AMAP", QUERIED_AT, requested, requested, 0,
                    TransitStatus.OK, plans);
        });

        List<JourneyPlan> first = service.plan(request(DEPARTURE)).plans();
        List<JourneyPlan> second = service.plan(request(DEPARTURE)).plans();

        // 上游仅交换候选顺序时，每条路线的 planId、返回顺序与集合都保持不变。
        assertThat(planIdByLine(second)).isEqualTo(planIdByLine(first));
        assertThat(ids(second)).isEqualTo(ids(first));
        assertThat(lineIds(second)).isEqualTo(lineIds(first));
    }

    @Test
    void equalMetricPlansKeepStableTopThreeWhenProviderShufflesOrder() {
        givenConnectors(connector("gate_east", "东门", 800.0, 600, EXIT_POINT));
        List<TransitPlan> candidates = List.of(
                planVia("line_2", "往龙泉驿方向", "犀浦", "天府广场", 1200, 300, 1),
                planVia("line_6", "往望丛祠方向", "犀浦", "天府广场", 1200, 300, 1),
                planVia("line_7", "往十里店方向", "犀浦", "天府广场", 1200, 300, 1),
                planVia("bus_311", "往市区方向", "犀浦", "天府广场", 1200, 300, 1));
        givenProviderAnswer(requested -> new TransitSearchResult("AMAP", QUERIED_AT, requested,
                requested, 0, TransitStatus.OK, candidates));
        List<JourneyPlan> forward = service.plan(request(DEPARTURE)).plans();

        givenProviderAnswer(requested -> new TransitSearchResult("AMAP", QUERIED_AT, requested,
                requested, 0, TransitStatus.OK, candidates.reversed()));
        List<JourneyPlan> reversed = service.plan(request(DEPARTURE)).plans();

        // 四条路线指标完全相同：planId 由路线身份决定，前三名的集合与顺序不随上游顺序漂移。
        assertThat(forward).hasSize(JourneyPlanRanking.MAX_PLANS);
        assertThat(ids(reversed)).isEqualTo(ids(forward));
        assertThat(lineIds(reversed)).isEqualTo(lineIds(forward));
        assertThat(planIdByLine(reversed)).isEqualTo(planIdByLine(forward));
    }

    @Test
    void unknownDestinationReturns404() {
        when(placeService.getById(DESTINATION)).thenThrow(new PlaceNotFoundException(DESTINATION));

        expectError(DEPARTURE, HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", false);
    }

    @Test
    void originMustBeCampusPlace() {
        when(placeService.getById(ORIGIN)).thenReturn(cityPlace(ORIGIN, "天府广场"));

        expectError(DEPARTURE, HttpStatus.UNPROCESSABLE_ENTITY,
                "CAMPUS_CONNECTOR_UNAVAILABLE", false);
        verifyNoInteractions(connectorService);
    }

    @Test
    void destinationMustBeCityPlace() {
        when(placeService.getById(DESTINATION)).thenReturn(campusPlace(DESTINATION, "图书馆"));

        expectError(DEPARTURE, HttpStatus.UNPROCESSABLE_ENTITY, "PLACE_UNAVAILABLE", false);
    }

    @Test
    void pendingPlaceIsNotRoutable() {
        PlaceCandidate pending = new PlaceCandidate("swjtu_org_342", "图书馆", PlaceKind.CAMPUS,
                "office_or_library", List.of(), false, "缺少经核实的楼宇入口与可走路段",
                "UNVERIFIED",
                new GeoPosition("swjtu_org_342", 103.98, 30.76, "UNDECLARED",
                        "source_marker_not_verified_entrance"),
                null, "2026-11-15.2", List.of("S03"));
        when(placeService.getById(ORIGIN)).thenReturn(pending);

        JourneyApiException error = expectError(DEPARTURE, HttpStatus.UNPROCESSABLE_ENTITY,
                "PLACE_NOT_ROUTABLE", false);
        assertThat(error.getMessage()).contains("缺少经核实的楼宇入口");
    }

    @Test
    void unsupportedCoordinateSystemIsRejected() {
        when(placeService.getById(ORIGIN)).thenReturn(new PlaceCandidate(ORIGIN, "图书馆",
                PlaceKind.CAMPUS, "library", List.of(), true, null, "APPLICATION_VERIFIED",
                new GeoPosition(ORIGIN_POINT, 103.98, 30.76, "BD-09", "entrance"),
                new GeoPosition(ORIGIN_POINT, 103.98, 30.76, "BD-09", "entrance"),
                "2026-11-15.2", List.of()));

        expectError(DEPARTURE, HttpStatus.UNPROCESSABLE_ENTITY,
                "COORDINATE_SYSTEM_MISMATCH", false);
    }

    @Test
    void invalidRequestsAreRejectedBeforeAnyDataAccess() {
        expectErrorOn(null, HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", false);
        expectErrorOn(new JourneyRequest("", DESTINATION, DEPARTURE, "RECOMMENDED"),
                HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", false);
        expectError("2026-11-16 09:00", HttpStatus.BAD_REQUEST, "DEPARTURE_TIME_INVALID", false);
        expectErrorWithPreference("FASTEST", HttpStatus.BAD_REQUEST, "INVALID_PREFERENCE", false);
        verifyNoInteractions(transitProvider);
    }

    // ---------- helpers ----------

    private JourneyRequest request(String departure) {
        return new JourneyRequest(ORIGIN, DESTINATION, departure, "RECOMMENDED");
    }

    private JourneyApiException expectError(String departure, HttpStatus status, String code,
                                            boolean retryable) {
        return expectErrorOn(request(departure), status, code, retryable);
    }

    private JourneyApiException expectErrorOn(JourneyRequest request, HttpStatus status,
                                              String code, boolean retryable) {
        JourneyApiException error = catchThrowableOfType(() -> service.plan(request),
                JourneyApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(status);
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.retryable()).isEqualTo(retryable);
        assertThat(error.requestId()).startsWith("jr-");
        return error;
    }

    private void expectErrorWithPreference(String preference, HttpStatus status, String code,
                                           boolean retryable) {
        JourneyApiException error = catchThrowableOfType(
                () -> service.plan(new JourneyRequest(ORIGIN, DESTINATION, DEPARTURE, preference)),
                JourneyApiException.class);
        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(status);
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.retryable()).isEqualTo(retryable);
    }

    private void givenConnectors(CampusConnector... connectors) {
        when(connectorService.resolve(eq(ORIGIN), any(OffsetDateTime.class)))
                .thenReturn(new CampusConnectorService.Resolution(ORIGIN, "CAMPUS", "2026-11-15.4",
                        List.of(connectors), List.of()));
    }

    private void givenProviderSuccess(TransitPlan transitPlan) {
        givenProviderAnswer(requested -> new TransitSearchResult("AMAP", QUERIED_AT, requested,
                requested, 0, TransitStatus.OK, List.of(transitPlan)));
    }

    private void givenProviderError(TransitErrorCode code) {
        givenProviderAnswer(requested -> new TransitSearchResult("AMAP", QUERIED_AT, requested,
                requested, 0, TransitStatus.NO_ROUTE, List.of(), code, "上游失败"));
    }

    private void givenProviderAnswer(
            java.util.function.Function<OffsetDateTime, TransitSearchResult> answer) {
        when(transitProvider.search(any(TransitPoint.class), any(TransitPoint.class),
                any(OffsetDateTime.class), any(TransitPreference.class)))
                .thenAnswer(invocation -> answer.apply(invocation.getArgument(2)));
    }

    private JourneyRequest requestWithPreference(String preference) {
        return new JourneyRequest(ORIGIN, DESTINATION, DEPARTURE, preference);
    }

    private static List<String> ids(List<JourneyPlan> plans) {
        return plans.stream().map(JourneyPlan::planId).toList();
    }

    /** 按乘车线路标识稳定映射 planId，用于验证同一路线在候选换序后身份不变。 */
    private static Map<String, String> planIdByLine(List<JourneyPlan> plans) {
        Map<String, String> byLine = new LinkedHashMap<>();
        for (JourneyPlan plan : plans) {
            List<String> rides = lineIds(List.of(plan));
            byLine.put(rides.isEmpty() ? plan.planId() : rides.get(0), plan.planId());
        }
        return byLine;
    }

    private static List<String> gateIds(List<JourneyPlan> plans) {
        return plans.stream().map(JourneyPlan::gateId).toList();
    }

    private static List<Integer> totalDurations(List<JourneyPlan> plans) {
        return plans.stream().map(JourneyPlan::totalDurationSeconds).toList();
    }

    private static List<Integer> totalWalking(List<JourneyPlan> plans) {
        return plans.stream()
                .map(plan -> plan.totalWalkingMeters() == null
                        ? null : plan.totalWalkingMeters().intValue())
                .toList();
    }

    private static List<Integer> transferCounts(List<JourneyPlan> plans) {
        return plans.stream().map(JourneyPlan::transferCount).toList();
    }

    private static List<String> lineIds(List<JourneyPlan> plans) {
        return plans.stream()
                .flatMap(plan -> plan.segments().stream())
                .filter(segment -> segment.lineId() != null)
                .map(JourneySegment::lineId)
                .toList();
    }

    private static PlaceCandidate campusPlace(String id, String name) {
        GeoPosition routing = new GeoPosition(ORIGIN_POINT, 103.9832456, 30.7693165, "WGS84",
                "building_external_access_point");
        return new PlaceCandidate(id, name, PlaceKind.CAMPUS, "library", List.of(), true, null,
                "APPLICATION_VERIFIED", routing, routing, "2026-11-15.2", List.of());
    }

    private static PlaceCandidate cityPlace(String id, String name) {
        GeoPosition routing = new GeoPosition("osm_way_1395271422", 104.063299, 30.659841, "WGS84",
                "destination_point");
        return new PlaceCandidate(id, name, PlaceKind.CITY, "城市中心", List.of(), true, null,
                "APPLICATION_VERIFIED", routing, routing, "2026-11-15.2", List.of());
    }

    private static CampusConnector connector(String gateId, String gateName, Double walkMeters,
                                             int durationSeconds, String exitPointId) {
        CampusConnectorPoint origin = new CampusConnectorPoint(ORIGIN_POINT, 103.9832456,
                30.7693165, "WGS84", "building_external_access_point", List.of());
        CampusConnectorPoint exit = new CampusConnectorPoint(exitPointId, 103.9921, 30.7559,
                "WGS84", "gate_exit_point", List.of());
        CampusConnectorSegment segment = new CampusConnectorSegment("seg_" + gateId, 1, "步行至出校点",
                ORIGIN_POINT, exitPointId, walkMeters, durationSeconds, "footway", null,
                "OPEN", null,
                new CampusConnectorGeometry("WGS84", List.of(
                        List.of(103.9832456, 30.7693165), List.of(103.9921, 30.7559))),
                List.of(), List.of("S04"));
        return new CampusConnector("connector_" + gateId, ORIGIN, "西南交通大学犀浦校区图书馆",
                origin, gateId, gateName, List.of(), exit, List.of(segment), walkMeters,
                durationSeconds, "1.33 m/s", "APPLICATION_VERIFIED", "2026-11-15", "团队确认",
                List.of(), List.of(), null, List.of("S04"), null, null);
    }

    private static TransitPlan plan(Integer durationSeconds, Integer walkingMeters,
                                    Integer transferCount, BigDecimal costYuan) {
        TransitSegment walk = new TransitSegment("WALK", 300, 250, null, null, List.of(), null,
                "AMAP_V3_TRANSIT_INTEGRATED");
        TransitLine line = new TransitLine("地铁2号线", "line_2", "SUBWAY", "往龙泉驿方向",
                "犀浦", "天府广场", 1200, 8, null, "AMAP_V3_TRANSIT_INTEGRATED");
        TransitSegment subway = new TransitSegment("SUBWAY", 1200, null, "犀浦", "天府广场",
                List.of(line), null, "AMAP_V3_TRANSIT_INTEGRATED");
        return new TransitPlan(durationSeconds, walkingMeters, transferCount, costYuan,
                List.of(walk, subway), "AMAP_V3_TRANSIT_INTEGRATED");
    }

    /** 指定线路与上下车站的城市方案，用于构造不同去重签名的候选。 */
    private static TransitPlan planVia(String lineId, String direction, String boarding,
                                       String alighting, Integer durationSeconds,
                                       Integer walkingMeters, Integer transferCount) {
        TransitSegment walk = new TransitSegment("WALK", 300, 250, null, null, List.of(), null,
                "AMAP_V3_TRANSIT_INTEGRATED");
        TransitLine line = new TransitLine(lineId + "线", lineId, "SUBWAY", direction,
                boarding, alighting, 1200, 8, null, "AMAP_V3_TRANSIT_INTEGRATED");
        TransitSegment subway = new TransitSegment("SUBWAY", 1200, null, boarding, alighting,
                List.of(line), null, "AMAP_V3_TRANSIT_INTEGRATED");
        return new TransitPlan(durationSeconds, walkingMeters, transferCount,
                new BigDecimal("5.00"), List.of(walk, subway), "AMAP_V3_TRANSIT_INTEGRATED");
    }
}
