package cn.swjtu.zhixing.campus;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute")
@AutoConfigureMockMvc
class PlaceControllerTests {

    @DynamicPropertySource
    static void campusDataRoot(DynamicPropertyRegistry registry) {
        registry.add("campus.data-root", () -> TestDataRoot.repoRoot().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void searchLibraryInCampusScopeReturnsOfficialPlace() throws Exception {
        mockMvc.perform(get("/api/places").param("keyword", "图书馆").param("scope", "CAMPUS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("swjtu_org_342"))
                .andExpect(jsonPath("$.items[0].name").value("图书馆"))
                .andExpect(jsonPath("$.items[0].kind").value("CAMPUS"))
                .andExpect(jsonPath("$.items[0].category").value("office_or_library"))
                .andExpect(jsonPath("$.items[0].routingReady").value(false))
                .andExpect(jsonPath("$.items[0].routingPosition").value(nullValue()))
                .andExpect(jsonPath("$.items[0].unavailableReason").isNotEmpty())
                .andExpect(jsonPath("$.items[0].verificationStatus").value("DISPLAY_POSITION_VERIFIED"))
                .andExpect(jsonPath("$.items[0].displayPosition.coordinateSystem").value("GCJ-02"))
                .andExpect(jsonPath("$.items[0].displayPosition.positionRole").value("amap_building_poi"))
                .andExpect(jsonPath("$.items[0].displayPosition.longitude").isNumber())
                .andExpect(jsonPath("$.items[0].displayPosition.latitude").isNumber())
                .andExpect(jsonPath("$.items[0].dataVersion").value("2026-11-19.2"))
                .andExpect(jsonPath("$.items[0].sourceRefs[0]").value("S03"))
                .andExpect(jsonPath("$.items[0].aliases").isArray());
    }

    @Test
    void searchEastGateReturnsSingleRoutableGate() throws Exception {
        mockMvc.perform(get("/api/places").param("keyword", "东门").param("scope", "GATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("campus_gate_east"))
                .andExpect(jsonPath("$.items[0].kind").value("GATE"))
                .andExpect(jsonPath("$.items[0].routingReady").value(true))
                .andExpect(jsonPath("$.items[0].routingPosition.pointId").value("osm_node_13469658219"))
                .andExpect(jsonPath("$.items[0].routingPosition.longitude").value(103.991294))
                .andExpect(jsonPath("$.items[0].routingPosition.latitude").value(30.771698))
                .andExpect(jsonPath("$.items[0].routingPosition.coordinateSystem").value("WGS84"))
                .andExpect(jsonPath("$.items[0].routingPosition.positionRole").value("gate_exit"))
                .andExpect(jsonPath("$.items[0].verificationStatus").value("APPLICATION_VERIFIED"))
                .andExpect(jsonPath("$.items[0].unavailableReason").value(nullValue()));
    }

    @Test
    void searchEastGateAliasReturnsSameEntity() throws Exception {
        mockMvc.perform(get("/api/places").param("keyword", "东大门").param("scope", "GATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("campus_gate_east"));
    }

    @Test
    void searchCityDestinationReturnsRoutableCandidate() throws Exception {
        mockMvc.perform(get("/api/places").param("keyword", "天府广场").param("scope", "CITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("C01"))
                .andExpect(jsonPath("$.items[0].name").value("天府广场"))
                .andExpect(jsonPath("$.items[0].kind").value("CITY"))
                .andExpect(jsonPath("$.items[0].routingReady").value(true))
                .andExpect(jsonPath("$.items[0].routingPosition.pointId").value("osm_way_1395271422"))
                .andExpect(jsonPath("$.items[0].routingPosition.coordinateSystem").value("WGS84"))
                .andExpect(jsonPath("$.items[0].routingPosition.positionRole").value("destination_point"))
                .andExpect(jsonPath("$.items[0].dataVersion").value("2026-11-19.2"));
    }

    @Test
    void unknownKeywordReturnsEmptyItemsWith200() throws Exception {
        mockMvc.perform(get("/api/places").param("keyword", "绝对不存在的地方xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void omittedKeywordListsAllCandidates() throws Exception {
        mockMvc.perform(get("/api/places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(220));
    }

    @Test
    void unknownScopeReturns400InvalidScope() throws Exception {
        mockMvc.perform(get("/api/places").param("keyword", "图书馆").param("scope", "FOO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCOPE"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void getPlaceByIdReturnsFullCandidate() throws Exception {
        mockMvc.perform(get("/api/places/swjtu_org_100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("swjtu_org_100"))
                .andExpect(jsonPath("$.name").value("材料学院实验教学中心"))
                .andExpect(jsonPath("$.category").value("teaching_or_lab"))
                .andExpect(jsonPath("$.routingReady").value(false))
                .andExpect(jsonPath("$.routingPosition").value(nullValue()))
                .andExpect(jsonPath("$.unavailableReason").isNotEmpty())
                .andExpect(jsonPath("$.displayPosition.positionRole").value("amap_building_poi"));
    }

    @Test
    void unknownIdReturns404PlaceNotFound() throws Exception {
        mockMvc.perform(get("/api/places/not_a_place"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not_a_place")));
    }

    @Test
    void demoOriginsExposeSelectableExplicitRoutingPoints() throws Exception {
        Map<String, String> displayRoles = Map.of(
                "osm_way_1098786977", "amap_building_poi",
                "osm_way_1099340057", "footprint_centroid_not_entrance");
        for (var origin : displayRoles.entrySet()) {
            mockMvc.perform(get("/api/places/" + origin.getKey()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.routingReady").value(true))
                    .andExpect(jsonPath("$.routingPosition.positionRole").value("building_external_access_point"))
                    .andExpect(jsonPath("$.displayPosition.positionRole").value(origin.getValue()))
                    .andExpect(jsonPath("$.dataVersion").value("2026-11-19.2"));
        }
    }
}
