package cn.swjtu.zhixing.journey;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP 边界与错误体契约：请求/响应字段名、200 NO_ROUTE、400/404/422/502/504 与 retryable。 */
@WebMvcTest(JourneyController.class)
class JourneyControllerTests {

    private static final String REQUEST_JSON = """
            {"originId":"osm_way_1098786977","destinationId":"C01",
             "departureTime":"2026-11-16T15:16:00+08:00","preference":"RECOMMENDED"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JourneyService journeyService;

    @Test
    void successResponseExposesJourneyPlanContract() throws Exception {
        when(journeyService.plan(any())).thenReturn(response(JourneyStatus.SUCCEEDED,
                List.of(plan()), List.of()));

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("jr-controller-test"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.provider").value("AMAP"))
                .andExpect(jsonPath("$.campusDataVersion").value("places=2026-11-15.2;connectors=2026-11-15.4"))
                .andExpect(jsonPath("$.queriedAt").value("2026-11-16T15:20:00+08:00"))
                .andExpect(jsonPath("$.plans[0].planId").value("plan_0123456789abcdef"))
                .andExpect(jsonPath("$.plans[0].gateId").value("gate_east"))
                .andExpect(jsonPath("$.plans[0].gateExitPoint.pointId").value("osm_node_13469658219"))
                .andExpect(jsonPath("$.plans[0].departureTime").value("2026-11-16T15:16:00+08:00"))
                .andExpect(jsonPath("$.plans[0].cityDepartureTime").value("2026-11-16T15:16:00+08:00"))
                .andExpect(jsonPath("$.plans[0].providerDepartureTime").value("2026-11-16T15:16:00+08:00"))
                .andExpect(jsonPath("$.plans[0].roundingWaitSeconds").value(0))
                .andExpect(jsonPath("$.plans[0].arrivalTime").value("2026-11-16T16:26:56+08:00"))
                .andExpect(jsonPath("$.plans[0].totalDurationSeconds").value(4256))
                .andExpect(jsonPath("$.plans[0].totalWalkingMeters").value(2248.0))
                .andExpect(jsonPath("$.plans[0].transferCount").value(1))
                .andExpect(jsonPath("$.plans[0].costYuan").value(5.0))
                .andExpect(jsonPath("$.plans[0].segments[0].sequence").value(1))
                .andExpect(jsonPath("$.plans[0].segments[0].mode").value("CAMPUS_WALK"))
                .andExpect(jsonPath("$.plans[0].segments[0].coordinateSystem").value("WGS84"))
                .andExpect(jsonPath("$.plans[0].sources[0]").value("campus_connector:connector_library_east_gate"))
                .andExpect(jsonPath("$.warnings").doesNotExist());
    }

    @Test
    void validQueryWithoutPlansReturns200NoRoute() throws Exception {
        when(journeyService.plan(any())).thenReturn(response(JourneyStatus.NO_ROUTE, List.of(), List.of()));

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_ROUTE"))
                .andExpect(jsonPath("$.plans").isArray())
                .andExpect(jsonPath("$.plans").isEmpty());
    }

    @Test
    void warningsAreReturnedWhenSomeGatesFailed() throws Exception {
        when(journeyService.plan(any())).thenReturn(response(JourneyStatus.SUCCEEDED,
                List.of(plan()), List.of("校门 gate_west 的出校点城市段查询失败（PROVIDER_ERROR）")));

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0]").value(
                        "校门 gate_west 的出校点城市段查询失败（PROVIDER_ERROR）"));
    }

    @Test
    void businessErrorIsMappedWithRequestIdCodeMessageAndRetryable() throws Exception {
        when(journeyService.plan(any())).thenThrow(new JourneyApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "CAMPUS_CONNECTOR_UNAVAILABLE",
                "起点“图书馆”当前时刻没有可用校园接驳", false, "jr-error-422"));

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.requestId").value("jr-error-422"))
                .andExpect(jsonPath("$.code").value("CAMPUS_CONNECTOR_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("起点“图书馆”当前时刻没有可用校园接驳"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void providerTimeoutMapsTo504WithRetryable() throws Exception {
        when(journeyService.plan(any())).thenThrow(new JourneyApiException(
                HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT", "上游城市公共交通服务超时",
                true, "jr-error-504"));

        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_JSON))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("PROVIDER_TIMEOUT"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void malformedJsonIsRejectedAsInvalidParameter() throws Exception {
        mockMvc.perform(post("/api/journeys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private static JourneyResponse response(JourneyStatus status, List<JourneyPlan> plans,
                                            List<String> warnings) {
        return new JourneyResponse("jr-controller-test", status, plans,
                OffsetDateTime.parse("2026-11-16T15:20:00+08:00"),
                "places=2026-11-15.2;connectors=2026-11-15.4", "AMAP", warnings);
    }

    private static JourneyPlan plan() {
        JourneyPoint exit = new JourneyPoint("osm_node_13469658219", "东门出校点", 103.9921,
                30.7559, "WGS84", "gate_exit_point");
        JourneySegment campus = new JourneySegment(1, "CAMPUS_WALK", "从图书馆步行至东门出校点",
                new JourneyPoint("osm_node_5167087319", "图书馆", 103.9832456, 30.7693165,
                        "WGS84", "building_external_access_point"),
                exit, null, null, null, null, null, 879.07, 664,
                new JourneyGeometry("LineString", List.of(
                        List.of(103.9832456, 30.7693165), List.of(103.9921, 30.7559)), "WGS84"),
                "WGS84");
        JourneySegment metro = new JourneySegment(2, "METRO", "乘坐地铁2号线",
                new JourneyPoint(null, "犀浦", null, null, null, null),
                new JourneyPoint(null, "天府广场", null, null, null, null),
                "line_2", "地铁2号线", "往龙泉驿方向", "犀浦", "天府广场", null, 1200, null, null);
        return new JourneyPlan("plan_0123456789abcdef", "osm_way_1098786977", "C01", "gate_east",
                exit, OffsetDateTime.parse("2026-11-16T15:16:00+08:00"),
                OffsetDateTime.parse("2026-11-16T15:16:00+08:00"),
                OffsetDateTime.parse("2026-11-16T15:16:00+08:00"), 0,
                OffsetDateTime.parse("2026-11-16T16:26:56+08:00"), 4256,
                new BigDecimal("2248"), 1, new BigDecimal("5.00"),
                List.of(campus, metro), List.of("campus_connector:connector_library_east_gate"),
                OffsetDateTime.parse("2026-11-16T15:20:00+08:00"),
                "places=2026-11-15.2;connectors=2026-11-15.4");
    }
}
