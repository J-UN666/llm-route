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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 复审 P1 回归：连接虽为 APPLICATION_VERIFIED、分段与数字完整，但校门明确禁止行人通行
 * （accessRules.pedestrianAccess=PROHIBITED）或含 PROHIBITED 分段时，不得返回可用接驳；
 * 必须返回 422 NO_APPROVED_CONNECTOR 及明确原因。
 *
 * <p>夹具模拟驳回方“临时把 pedestrianAccess 改为 PROHIBITED”的复现步骤，不写入应用数据。</p>
 */
@SpringBootTest(properties = "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute")
@AutoConfigureMockMvc
@TestPropertySource(
        properties = "campus.application-connectors=classpath:data/campus-connectors-prohibited-access-only.json")
class CampusConnectorProhibitedAccessTests {

    private static final String LIBRARY = "osm_way_1098786977";
    private static final String DEPARTURE = "2026-11-15T09:00:00+08:00";

    @DynamicPropertySource
    static void campusDataRoot(DynamicPropertyRegistry registry) {
        registry.add("campus.data-root", () -> TestDataRoot.repoRoot().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pedestrianProhibitedAccessRuleIsExcludedWithExplicitReason() throws Exception {
        mockMvc.perform(get("/api/campus/connectors")
                        .param("originId", LIBRARY)
                        .param("departureTime", DEPARTURE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_APPROVED_CONNECTOR"))
                .andExpect(jsonPath("$.originId").value(LIBRARY))
                .andExpect(jsonPath("$.originKind").value("CAMPUS"))
                .andExpect(jsonPath("$.dataVersion").value("test-fixture-prohibited-access-1"))
                .andExpect(jsonPath("$.unavailable", hasSize(2)))
                .andExpect(jsonPath("$.unavailable[0].connectorId").value("fixture_connector_pedestrian_prohibited"))
                .andExpect(jsonPath("$.unavailable[0].verificationStatus").value("APPLICATION_VERIFIED"))
                .andExpect(jsonPath("$.unavailable[0].reasonCode").value("PEDESTRIAN_ACCESS_PROHIBITED"))
                .andExpect(jsonPath("$.unavailable[0].reason", containsString("禁止行人通行")))
                .andExpect(jsonPath("$.unavailable[1].connectorId").value("fixture_connector_prohibited_segment"))
                .andExpect(jsonPath("$.unavailable[1].reasonCode").value("PROHIBITED_SEGMENT"));
    }
}
