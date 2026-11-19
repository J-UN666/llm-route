package cn.swjtu.zhixing.campus;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 使用项目当前的团队确认演示接驳验证 HTTP 200；禁行、关闭与待核实情况由独立夹具覆盖。 */
@SpringBootTest(properties = "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute")
@AutoConfigureMockMvc
class CampusConnectorControllerTests {

    private static final String LIBRARY = "osm_way_1098786977";
    private static final String DORMITORY = "osm_way_1099340057";
    private static final String DEPARTURE = "2026-11-15T09:00:00+08:00";

    @DynamicPropertySource
    static void campusDataRoot(DynamicPropertyRegistry registry) {
        registry.add("campus.data-root", () -> TestDataRoot.repoRoot().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void libraryReturnsTeamConfirmedConnector() throws Exception {
        mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", LIBRARY).param("departureTime", DEPARTURE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataVersion").value("2026-11-19.2"))
                .andExpect(jsonPath("$.connectors", hasSize(1)))
                .andExpect(jsonPath("$.connectors[0].id").value("connector_library_east_gate"))
                .andExpect(jsonPath("$.connectors[0].verificationStatus").value("APPLICATION_VERIFIED"))
                .andExpect(jsonPath("$.connectors[0].verificationMethod", containsString("团队确认")))
                .andExpect(jsonPath("$.connectors[0].originPoint.positionRole").value("building_external_access_point"))
                .andExpect(jsonPath("$.connectors[0].gateExitPoint.pointId").value("osm_node_13469658219"))
                .andExpect(jsonPath("$.connectors[0].walkMeters").value(879.07))
                .andExpect(jsonPath("$.connectors[0].durationSeconds").value(664))
                .andExpect(jsonPath("$.connectors[0].missingEvidence", hasSize(0)));
    }

    @Test
    void dormitoryReturnsTeamConfirmedConnector() throws Exception {
        mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", DORMITORY).param("departureTime", DEPARTURE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors", hasSize(1)))
                .andExpect(jsonPath("$.connectors[0].id").value("connector_dorm1_east_gate"))
                .andExpect(jsonPath("$.connectors[0].walkMeters").value(1807.87))
                .andExpect(jsonPath("$.connectors[0].durationSeconds").value(1369))
                .andExpect(jsonPath("$.connectors[0].accessRules.pedestrianAccess").value("PERMITTED"));
    }

    @Test
    void fixedSampleUrlFromTaskCardIsReplayable() throws Exception {
        mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", LIBRARY).param("departureTime", DEPARTURE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors[0].id").value("connector_library_east_gate"));
    }

    @Test
    void repeatedRequestsReturnIdenticalBodies() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", LIBRARY)
                        .param("departureTime", DEPARTURE))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", LIBRARY)
                        .param("departureTime", DEPARTURE))
                .andExpect(status().isOk()).andReturn();
        String firstBody = first.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String secondBody = second.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(secondBody).isEqualTo(firstBody);
    }

    @Test
    void unknownPlaceReturns404() throws Exception {
        mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", "unknown_origin_id")
                        .param("departureTime", DEPARTURE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
    }

    @Test
    void gateOriginReturns422WithExplicitOriginKind() throws Exception {
        mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", "campus_gate_east")
                        .param("departureTime", DEPARTURE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_APPROVED_CONNECTOR"))
                .andExpect(jsonPath("$.originId").value("campus_gate_east"))
                .andExpect(jsonPath("$.originKind").value("GATE"))
                .andExpect(jsonPath("$.message", containsString("不是校园起点")));
    }

    @Test
    void cityOriginReturns422WithExplicitOriginKind() throws Exception {
        mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", "C01")
                        .param("departureTime", DEPARTURE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_APPROVED_CONNECTOR"))
                .andExpect(jsonPath("$.originKind").value("CITY"));
    }

    @Test
    void missingOriginIdReturns400() throws Exception {
        mockMvc.perform(get("/api/campus/connectors").param("departureTime", DEPARTURE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORIGIN_ID"));
    }

    @Test
    void missingDepartureTimeReturns400() throws Exception {
        mockMvc.perform(get("/api/campus/connectors").param("originId", LIBRARY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEPARTURE_TIME"));
    }

    @Test
    void invalidDepartureTimeReturns400WithFormatHint() throws Exception {
        mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", LIBRARY)
                        .param("departureTime", "2026-11-15 09:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEPARTURE_TIME"))
                .andExpect(jsonPath("$.message", containsString("%2B")));
    }
}
