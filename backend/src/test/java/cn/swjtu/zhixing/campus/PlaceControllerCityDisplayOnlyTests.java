package cn.swjtu.zhixing.campus;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 回归测试（2026-11-15 验收返工）：城市目的地只有未核实的展示位置、没有 routingPosition 时，
 * 接口必须保持 routingReady=false、routingPosition=null 并保留不可用原因，
 * 不得把 displayPosition 自动提升为算路点。
 */
@SpringBootTest(properties = "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute")
@AutoConfigureMockMvc
class PlaceControllerCityDisplayOnlyTests {

    private static final Path DATA_ROOT;
    private static final Path APPLICATION_JSON;

    private static final String OFFICIAL_PLACES = """
            [
              {
                "place_id": "official_fixture",
                "name": "测试地点",
                "category": "teaching_or_lab",
                "source_longitude": 103.9817,
                "source_latitude": 30.7729,
                "coordinate_system": "UNDECLARED",
                "position_role": "source_marker_not_verified_entrance",
                "source_id": "S03",
                "routing_ready": false
              }
            ]
            """;

    private static final String MVP_SEEDS = """
            [
              {
                "place_id": "seed_fixture",
                "name": "测试起点",
                "longitude": 103.98,
                "latitude": 30.76,
                "coordinate_system": "WGS84",
                "position_role": "footprint_centroid_not_entrance",
                "source_id": "S04"
              }
            ]
            """;

    private static final String CAMPUS_GATES = """
            {
              "gates": [
                {"id": "gate_fixture", "n": "测试门", "x": 103.98979, "y": 30.762968, "sourceIds": ["S04"]}
              ]
            }
            """;

    private static final String APPLICATION_PLACES = """
            {
              "dataVersion": "test-fixture-display-only",
              "places": [
                {
                  "id": "C_DISPLAY_ONLY",
                  "kind": "CITY",
                  "name": "仅展示位置目的地",
                  "verificationStatus": "UNVERIFIED",
                  "displayPosition": {
                    "pointId": "osm_way_fixture_centroid",
                    "longitude": 104.06,
                    "latitude": 30.65,
                    "coordinateSystem": "WGS84",
                    "positionRole": "footprint_centroid_not_entrance"
                  },
                  "sourceRefs": ["TEST"]
                }
              ]
            }
            """;

    static {
        try {
            DATA_ROOT = Files.createTempDirectory("w1-04-display-only-repo");
            Path cleaned = DATA_ROOT.resolve(Path.of("source-data", "校园数据", "cleaned"));
            Path webData = DATA_ROOT.resolve(Path.of("web", "data"));
            Files.createDirectories(cleaned);
            Files.createDirectories(webData);
            Files.writeString(cleaned.resolve("official_places.json"), OFFICIAL_PLACES, StandardCharsets.UTF_8);
            Files.writeString(cleaned.resolve("place_aliases.json"), "[]", StandardCharsets.UTF_8);
            Files.writeString(cleaned.resolve("mvp_seed_candidates.json"), MVP_SEEDS, StandardCharsets.UTF_8);
            Files.writeString(webData.resolve("campus.json"), CAMPUS_GATES, StandardCharsets.UTF_8);
            APPLICATION_JSON = Files.createTempFile("w1-04-display-only-application", ".json");
            Files.writeString(APPLICATION_JSON, APPLICATION_PLACES, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建城市展示点回归测试数据", ex);
        }
    }

    @DynamicPropertySource
    static void campusData(DynamicPropertyRegistry registry) {
        registry.add("campus.data-root", DATA_ROOT::toString);
        // 应用数据不在类路径中时，用显式 Resource 位置覆盖默认的 classpath:data/application-places.json。
        registry.add("campus.application-places", () -> APPLICATION_JSON.toUri().toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @AfterAll
    static void cleanUp() throws IOException {
        if (DATA_ROOT == null || !Files.exists(DATA_ROOT)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(DATA_ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响测试结果。
                }
            });
        }
    }

    @Test
    void getByIdKeepsDisplayOnlyCityNotRoutable() throws Exception {
        mockMvc.perform(get("/api/places/C_DISPLAY_ONLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("C_DISPLAY_ONLY"))
                .andExpect(jsonPath("$.kind").value("CITY"))
                .andExpect(jsonPath("$.routingReady").value(false))
                .andExpect(jsonPath("$.routingPosition").value(nullValue()))
                .andExpect(jsonPath("$.unavailableReason").isNotEmpty())
                .andExpect(jsonPath("$.displayPosition.pointId").value("osm_way_fixture_centroid"))
                .andExpect(jsonPath("$.displayPosition.coordinateSystem").value("WGS84"))
                .andExpect(jsonPath("$.displayPosition.positionRole").value("footprint_centroid_not_entrance"))
                .andExpect(jsonPath("$.verificationStatus").value("UNVERIFIED"));
    }

    @Test
    void searchCityScopeKeepsDisplayOnlyCityNotRoutable() throws Exception {
        mockMvc.perform(get("/api/places").param("scope", "CITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("C_DISPLAY_ONLY"))
                .andExpect(jsonPath("$.items[0].routingReady").value(false))
                .andExpect(jsonPath("$.items[0].routingPosition").value(nullValue()))
                .andExpect(jsonPath("$.items[0].unavailableReason").isNotEmpty());
    }
}
