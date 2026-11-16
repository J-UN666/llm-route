package cn.swjtu.zhixing.journey;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.swjtu.zhixing.transit.TransitGeometry;
import cn.swjtu.zhixing.transit.TransitLine;
import cn.swjtu.zhixing.transit.TransitPlan;
import cn.swjtu.zhixing.transit.TransitPoint;
import cn.swjtu.zhixing.transit.TransitPreference;
import cn.swjtu.zhixing.transit.TransitProvider;
import cn.swjtu.zhixing.transit.TransitSearchResult;
import cn.swjtu.zhixing.transit.TransitSegment;
import cn.swjtu.zhixing.transit.TransitStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 端到端组合验收（W1-08 验收 D 的离线部分）：真实审核 JSON（W1-04 地点 + W1-05 接驳）作为输入，
 * 高德城市段用桩 provider 注入 W1-06 真实调用记录中的方案数值（4256 秒 / 2248 米 / 换乘 1 次 / 5 元），
 * 核对同门衔接、端点、时间推进与总量合计。
 *
 * <p>真实高德调用需要 {@code AMAP_WEB_SERVICE_KEY}；本环境未配置，未验证项在 docs/contracts/journey-api.md 标注。</p>
 */
@SpringBootTest(properties = "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute")
@AutoConfigureMockMvc
class JourneyApiIntegrationTests {

    private static final String LIBRARY = "osm_way_1098786977";
    private static final String TIENS = "C01";
    private static final String DEPARTURE = "2026-11-16T09:00:00+08:00";
    private static final String EXIT_POINT = "osm_node_13469658219";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @DynamicPropertySource
    static void campusDataRoot(DynamicPropertyRegistry registry) {
        registry.add("campus.data-root", () -> JourneyTestDataRoot.repoRoot().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransitProvider transitProvider;

    @BeforeEach
    void stubProviderWithRecordedAmapPlan() {
        when(transitProvider.search(any(TransitPoint.class), any(TransitPoint.class),
                any(OffsetDateTime.class), any(TransitPreference.class)))
                .thenAnswer(invocation -> {
                    OffsetDateTime requested = invocation.getArgument(2);
                    OffsetDateTime local = requested.atZoneSameInstant(BUSINESS_ZONE)
                            .toOffsetDateTime();
                    OffsetDateTime rounded = local.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
                    int roundingWait = (int) (rounded.toEpochSecond() - local.toEpochSecond());
                    return new TransitSearchResult("AMAP",
                            OffsetDateTime.parse("2026-11-16T01:20:00Z"),
                            requested, rounded, roundingWait, TransitStatus.OK,
                            List.of(recordedPlan()));
                });
    }

    @Test
    void realCampusDataAndProviderPlanComposeOneFullJourney() throws Exception {
        JsonNode body = postJourney(requestBody(LIBRARY, TIENS, DEPARTURE, "RECOMMENDED"));

        org.assertj.core.api.Assertions.assertThat(body.path("status").asText()).isEqualTo("SUCCEEDED");
        org.assertj.core.api.Assertions.assertThat(body.path("campusDataVersion").asText())
                .isEqualTo("places=2026-11-15.2;connectors=2026-11-15.4");
        org.assertj.core.api.Assertions.assertThat(body.path("provider").asText()).isEqualTo("AMAP");
        JsonNode plan = body.path("plans").get(0);
        org.assertj.core.api.Assertions.assertThat(plan.path("gateId").asText()).isEqualTo("campus_gate_east");
        org.assertj.core.api.Assertions.assertThat(plan.path("gateExitPoint").path("pointId").asText())
                .isEqualTo(EXIT_POINT);
        org.assertj.core.api.Assertions.assertThat(plan.path("departureTime").asText())
                .isEqualTo("2026-11-16T09:00:00+08:00");
        // 664 秒接驳后为 09:11:04，provider 取整到下一分钟 09:12:00，等待 56 秒。
        org.assertj.core.api.Assertions.assertThat(plan.path("cityDepartureTime").asText())
                .isEqualTo("2026-11-16T09:11:04+08:00");
        org.assertj.core.api.Assertions.assertThat(plan.path("providerDepartureTime").asText())
                .isEqualTo("2026-11-16T09:12:00+08:00");
        org.assertj.core.api.Assertions.assertThat(plan.path("roundingWaitSeconds").asInt()).isEqualTo(56);
        org.assertj.core.api.Assertions.assertThat(plan.path("arrivalTime").asText())
                .isEqualTo("2026-11-16T10:22:56+08:00");
        org.assertj.core.api.Assertions.assertThat(plan.path("totalDurationSeconds").asInt()).isEqualTo(4976);
        org.assertj.core.api.Assertions.assertThat(plan.path("totalWalkingMeters").asDouble())
                .isEqualTo(3127.07);
        org.assertj.core.api.Assertions.assertThat(plan.path("transferCount").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(plan.path("costYuan").asDouble()).isEqualTo(5.0);

        JsonNode campusSegment = plan.path("segments").get(0);
        org.assertj.core.api.Assertions.assertThat(campusSegment.path("sequence").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(campusSegment.path("mode").asText())
                .isEqualTo("CAMPUS_WALK");
        org.assertj.core.api.Assertions.assertThat(campusSegment.path("distanceMeters").asDouble())
                .isEqualTo(879.07);
        org.assertj.core.api.Assertions.assertThat(campusSegment.path("durationSeconds").asInt())
                .isEqualTo(664);
        org.assertj.core.api.Assertions.assertThat(campusSegment.path("coordinateSystem").asText())
                .isEqualTo("WGS84");
        org.assertj.core.api.Assertions.assertThat(campusSegment.path("fromPoint").path("pointId").asText())
                .isEqualTo("osm_node_5167087319");
        org.assertj.core.api.Assertions.assertThat(campusSegment.path("toPoint").path("pointId").asText())
                .isEqualTo(EXIT_POINT);
        org.assertj.core.api.Assertions.assertThat(campusSegment.path("geometry").path("type").asText())
                .isEqualTo("LineString");
        org.assertj.core.api.Assertions.assertThat(
                campusSegment.path("geometry").path("coordinates").size() > 2).isTrue();

        org.assertj.core.api.Assertions.assertThat(plan.path("segments").get(1).path("mode").asText())
                .isEqualTo("WALK");
        JsonNode metro = plan.path("segments").get(2);
        org.assertj.core.api.Assertions.assertThat(metro.path("mode").asText()).isEqualTo("METRO");
        org.assertj.core.api.Assertions.assertThat(metro.path("lineName").asText())
                .isEqualTo("地铁2号线");
        org.assertj.core.api.Assertions.assertThat(metro.path("coordinateSystem").asText())
                .isEqualTo("GCJ-02");

        // 同一查询重复执行 planId 稳定；不同查询（改终点）planId 不同。
        JsonNode second = postJourney(requestBody(LIBRARY, TIENS, DEPARTURE, "RECOMMENDED"));
        org.assertj.core.api.Assertions.assertThat(second.path("plans").get(0).path("planId").asText())
                .isEqualTo(plan.path("planId").asText());
        org.assertj.core.api.Assertions.assertThat(plan.path("planId").asText())
                .matches("plan_[0-9a-f]{16}");
    }

    @Test
    void providerEmptyRouteReturns200NoRoute() throws Exception {
        when(transitProvider.search(any(TransitPoint.class), any(TransitPoint.class),
                any(OffsetDateTime.class), any(TransitPreference.class)))
                .thenReturn(new TransitSearchResult("AMAP",
                        OffsetDateTime.parse("2026-11-16T01:20:00Z"),
                        OffsetDateTime.parse(DEPARTURE), OffsetDateTime.parse(DEPARTURE), 0,
                        TransitStatus.NO_ROUTE, List.of()));

        JsonNode body = postJourney(requestBody(LIBRARY, TIENS, DEPARTURE, "RECOMMENDED"));

        org.assertj.core.api.Assertions.assertThat(body.path("status").asText()).isEqualTo("NO_ROUTE");
        org.assertj.core.api.Assertions.assertThat(body.path("plans").size()).isZero();
    }

    @Test
    void unknownDestinationReturns404WithErrorBody() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(LIBRARY, "C99", DEPARTURE, "RECOMMENDED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void cityPlaceAsOriginReturns422() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(TIENS, TIENS, DEPARTURE, "RECOMMENDED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMPUS_CONNECTOR_UNAVAILABLE"));
    }

    @Test
    void campusPlaceAsDestinationReturns422() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(LIBRARY, LIBRARY, DEPARTURE, "RECOMMENDED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLACE_UNAVAILABLE"));
    }

    @Test
    void pendingCampusPlaceReturns422NotRoutable() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("swjtu_org_342", TIENS, DEPARTURE, "RECOMMENDED")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_ROUTABLE"));
    }

    @Test
    void invalidDepartureTimeReturns400WithDistinctCode() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(LIBRARY, TIENS, "2026-11-16 09:00", "RECOMMENDED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEPARTURE_TIME_INVALID"));
    }

    @Test
    void invalidPreferenceReturns400() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(LIBRARY, TIENS, DEPARTURE, "FASTEST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PREFERENCE"));
    }

    @Test
    void malformedJsonReturns400InvalidParameter() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void providerIsCalledWithGateExitPointAndCityDeparture() throws Exception {
        postJourney(requestBody(LIBRARY, TIENS, DEPARTURE, "RECOMMENDED"));

        org.mockito.ArgumentCaptor<TransitPoint> origin =
                org.mockito.ArgumentCaptor.forClass(TransitPoint.class);
        org.mockito.ArgumentCaptor<OffsetDateTime> departure =
                org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(transitProvider).search(origin.capture(), any(TransitPoint.class),
                departure.capture(), any(TransitPreference.class));
        org.assertj.core.api.Assertions.assertThat(origin.getValue().pointId()).isEqualTo(EXIT_POINT);
        org.assertj.core.api.Assertions.assertThat(origin.getValue().coordinateSystem())
                .isEqualTo("WGS84");
        org.assertj.core.api.Assertions.assertThat(departure.getValue())
                .isEqualTo(OffsetDateTime.parse("2026-11-16T09:11:04+08:00"));
    }

    private JsonNode postJourney(String requestJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String requestBody(String originId, String destinationId, String departureTime,
                                      String preference) {
        return "{\"originId\":\"" + originId + "\",\"destinationId\":\"" + destinationId
                + "\",\"departureTime\":\"" + departureTime + "\",\"preference\":\""
                + preference + "\"}";
    }

    /** W1-06 真实高德调用记录中的第一方案：4256 秒 / 2248 米 / 换乘 1 次 / 5 元。 */
    private static TransitPlan recordedPlan() {
        TransitGeometry walkGeometry = new TransitGeometry("GCJ-02", List.of(
                List.of(103.9931, 30.7558), List.of(103.9945, 30.7561)));
        TransitSegment walk = new TransitSegment("WALK", 180, 250, null, null, List.of(),
                walkGeometry, "AMAP_V3_TRANSIT_INTEGRATED");
        TransitGeometry metroGeometry = new TransitGeometry("GCJ-02", List.of(
                List.of(103.9945, 30.7561), List.of(104.0633, 30.6598)));
        TransitLine line = new TransitLine("地铁2号线", "line_2", "SUBWAY", "往龙泉驿方向",
                "犀浦", "天府广场", 3800, 8, metroGeometry, "AMAP_V3_TRANSIT_INTEGRATED");
        TransitSegment subway = new TransitSegment("SUBWAY", 3800, null, "犀浦", "天府广场",
                List.of(line), metroGeometry, "AMAP_V3_TRANSIT_INTEGRATED");
        return new TransitPlan(4256, 2248, 1, new BigDecimal("5.00"), List.of(walk, subway),
                "AMAP_V3_TRANSIT_INTEGRATED");
    }
}
