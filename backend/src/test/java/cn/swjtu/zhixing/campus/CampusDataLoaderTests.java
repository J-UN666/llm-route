package cn.swjtu.zhixing.campus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

class CampusDataLoaderTests {

    private static final String FIXTURE_OFFICIAL_PLACES = """
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

    private static final String FIXTURE_SEED_CANDIDATES = """
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

    private static final String FIXTURE_CAMPUS_JSON = """
            {
              "gates": [
                {"id": "gate_fixture", "n": "测试门", "x": 103.98979, "y": 30.762968, "sourceIds": ["S04"]}
              ]
            }
            """;

    private static final String FIXTURE_APPLICATION_JSON = """
            {
              "dataVersion": "test-fixture-1",
              "places": [
                {
                  "id": "C_TEST",
                  "kind": "CITY",
                  "name": "测试目的地",
                  "verificationStatus": "APPLICATION_VERIFIED",
                  "verifiedAt": "2026-11-15",
                  "routingPosition": {
                    "pointId": "osm_fixture_point",
                    "longitude": 104.06,
                    "latitude": 30.65,
                    "coordinateSystem": "WGS84",
                    "positionRole": "destination_point"
                  },
                  "sourceRefs": ["TEST"]
                }
              ]
            }
            """;

    /** 城市目的地只有展示位置（未核实质心）、没有 routingPosition 的应用数据。 */
    private static final String FIXTURE_DISPLAY_ONLY_APPLICATION_JSON = """
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
                },
                {
                  "id": "C_DISPLAY_ONLY_WITH_REASON",
                  "kind": "CITY",
                  "name": "仅展示位置且注明原因的目的地",
                  "unavailableReason": "人工记录：落客点尚未核对",
                  "verificationStatus": "UNVERIFIED",
                  "displayPosition": {
                    "pointId": "osm_way_fixture_centroid_2",
                    "longitude": 104.07,
                    "latitude": 30.66,
                    "coordinateSystem": "WGS84",
                    "positionRole": "footprint_centroid_not_entrance"
                  },
                  "sourceRefs": ["TEST"]
                }
              ]
            }
            """;

    @Test
    void loadsProjectDataAndIndexesAllSources() {
        CampusDataLoader loader = projectLoader();
        assertThat(loader.isLoaded()).isTrue();
        assertThat(loader.loadErrorMessage()).isNull();
        List<PlaceCandidate> candidates = loader.candidates();
        assertThat(candidates).hasSize(220);
        assertThat(loader.dataVersion()).isEqualTo("2026-11-15.2");

        Map<PlaceKind, Long> counts = candidates.stream()
                .collect(Collectors.groupingBy(PlaceCandidate::kind, Collectors.counting()));
        assertThat(counts)
                .containsEntry(PlaceKind.CAMPUS, 209L)
                .containsEntry(PlaceKind.GATE, 6L)
                .containsEntry(PlaceKind.CITY, 5L);
        assertThat(loader.findById("swjtu_org_342")).isPresent();
        assertThat(loader.findById("campus_gate_east")).isPresent();
        assertThat(loader.findById("C01")).isPresent();

        PlaceCandidate official = loader.findById("swjtu_org_342").orElseThrow();
        assertThat(official.kind()).isEqualTo(PlaceKind.CAMPUS);
        assertThat(official.displayPosition().coordinateSystem()).isEqualTo("UNDECLARED");
        assertThat(official.displayPosition().positionRole()).isEqualTo("source_marker_not_verified_entrance");
        assertThat(official.routingReady()).isFalse();
        assertThat(official.routingPosition()).isNull();
        assertThat(official.unavailableReason()).isNotBlank();

        PlaceCandidate eastGate = loader.findById("campus_gate_east").orElseThrow();
        assertThat(eastGate.kind()).isEqualTo(PlaceKind.GATE);
        assertThat(eastGate.aliases()).contains("东大门");
        assertThat(eastGate.routingReady()).isTrue();
        assertThat(eastGate.routingPosition().pointId()).isEqualTo("osm_node_13469658219");
    }

    @Test
    void reloadKeepsCandidatesVersionAndStableIds() {
        CampusDataLoader loader = projectLoader();
        List<PlaceCandidate> first = loader.candidates();
        String firstVersion = loader.dataVersion();

        loader.load();

        assertThat(loader.isLoaded()).isTrue();
        assertThat(loader.dataVersion()).isEqualTo(firstVersion);
        assertThat(loader.candidates()).isEqualTo(first);
        assertThat(loader.candidates().stream().map(PlaceCandidate::id).distinct().count())
                .isEqualTo(first.size());
    }

    @Test
    void missingDataRootExposesConfigurationHint() {
        CampusDataLoader loader = new CampusDataLoader("", applicationResource(), new ObjectMapper());
        loader.load();

        assertThat(loader.isLoaded()).isFalse();
        assertThat(loader.loadErrorMessage())
                .contains("CAMPUS_DATA_ROOT")
                .contains("export CAMPUS_DATA_ROOT=\"$PWD\"");
        assertThatThrownBy(loader::candidates)
                .isInstanceOf(PlaceDataLoadException.class)
                .hasMessageContaining("地点数据尚未成功加载");
    }

    @Test
    void missingSourceFileIsNamedInLoadError(@TempDir Path tempDir) throws IOException {
        Path dataRoot = createFixtureDataRoot(tempDir);
        Files.delete(dataRoot.resolve(Path.of("source-data", "校园数据", "cleaned", "place_aliases.json")));

        CampusDataLoader loader = loader(dataRoot, fixtureApplicationResource());
        loader.load();

        assertThat(loader.isLoaded()).isFalse();
        assertThat(loader.loadErrorMessage())
                .contains("place_aliases.json")
                .contains("校园地点数据文件缺失")
                .contains("CAMPUS_DATA_ROOT");
    }

    @Test
    void malformedOfficialPlacesJsonNamesTheFile(@TempDir Path tempDir) throws IOException {
        Path dataRoot = createFixtureDataRoot(tempDir);
        Files.writeString(
                dataRoot.resolve(Path.of("source-data", "校园数据", "cleaned", "official_places.json")),
                "{not-json",
                StandardCharsets.UTF_8);

        CampusDataLoader loader = loader(dataRoot, fixtureApplicationResource());
        loader.load();

        assertThat(loader.isLoaded()).isFalse();
        assertThat(loader.loadErrorMessage())
                .contains("official_places.json")
                .contains("JSON 解析失败");
    }

    @Test
    void malformedApplicationJsonNamesTheResource(@TempDir Path tempDir) throws IOException {
        Path dataRoot = createFixtureDataRoot(tempDir);
        Resource brokenApplication = new ByteArrayResource("{not-json}".getBytes(StandardCharsets.UTF_8));

        CampusDataLoader loader = loader(dataRoot, brokenApplication);
        loader.load();

        assertThat(loader.isLoaded()).isFalse();
        assertThat(loader.loadErrorMessage())
                .contains("应用地点数据")
                .contains("JSON 解析失败");
    }

    @Test
    void applicationReferenceToUnknownBaseIdFails(@TempDir Path tempDir) throws IOException {
        Path dataRoot = createFixtureDataRoot(tempDir);
        String applicationJson = """
                {
                  "dataVersion": "test-fixture-2",
                  "places": [
                    {"id": "missing_place", "kind": "CAMPUS", "name": "没有基础数据的地点"}
                  ]
                }
                """;
        Resource application = new ByteArrayResource(applicationJson.getBytes(StandardCharsets.UTF_8));

        CampusDataLoader loader = loader(dataRoot, application);
        loader.load();

        assertThat(loader.isLoaded()).isFalse();
        assertThat(loader.loadErrorMessage())
                .contains("missing_place")
                .contains("不存在");
    }

    @Test
    void loadMinimalFixtureWithApplicationSupplement(@TempDir Path tempDir) throws IOException {
        CampusDataLoader loader = loader(createFixtureDataRoot(tempDir), fixtureApplicationResource());
        loader.load();

        assertThat(loader.isLoaded()).isTrue();
        assertThat(loader.dataVersion()).isEqualTo("test-fixture-1");
        assertThat(loader.candidates()).extracting(PlaceCandidate::id)
                .containsExactlyInAnyOrder("official_fixture", "seed_fixture", "gate_fixture", "C_TEST");
        PlaceCandidate city = loader.findById("C_TEST").orElseThrow();
        assertThat(city.kind()).isEqualTo(PlaceKind.CITY);
        assertThat(city.routingReady()).isTrue();
    }

    @Test
    void cityPlaceWithOnlyDisplayPositionKeepsNotRoutableState(@TempDir Path tempDir) throws Exception {
        CampusDataLoader loader = loader(createFixtureDataRoot(tempDir), displayOnlyApplicationResource());
        loader.load();

        assertThat(loader.isLoaded()).isTrue();
        PlaceCandidate displayOnly = loader.findById("C_DISPLAY_ONLY").orElseThrow();
        assertThat(displayOnly.kind()).isEqualTo(PlaceKind.CITY);
        assertThat(displayOnly.routingReady()).isFalse();
        assertThat(displayOnly.routingPosition()).isNull();
        assertThat(displayOnly.displayPosition().pointId()).isEqualTo("osm_way_fixture_centroid");
        assertThat(displayOnly.displayPosition().positionRole()).isEqualTo("footprint_centroid_not_entrance");
        assertThat(displayOnly.unavailableReason()).isNotBlank();
        assertThat(displayOnly.verificationStatus()).isEqualTo("UNVERIFIED");

        PlaceCandidate customReason = loader.findById("C_DISPLAY_ONLY_WITH_REASON").orElseThrow();
        assertThat(customReason.routingReady()).isFalse();
        assertThat(customReason.routingPosition()).isNull();
        assertThat(customReason.unavailableReason()).isEqualTo("人工记录：落客点尚未核对");

        // 接口 JSON 必须保持不可算路状态：routingPosition 为空、原因保留，展示点不得被提升为算路点。
        String json = new ObjectMapper().writeValueAsString(displayOnly);
        assertThat(json).contains("\"routingReady\":false");
        assertThat(json).contains("\"routingPosition\":null");
        assertThat(json).contains("\"unavailableReason\":\"");
        assertThat(json).contains("\"displayPosition\":{");
        assertThat(json).doesNotContain("\"routingPosition\":{\"pointId\"");
    }

    @Test
    void cityPlaceWithoutAnyPositionFailsLoad(@TempDir Path tempDir) throws IOException {
        String applicationJson = """
                {
                  "dataVersion": "test-fixture-no-position",
                  "places": [
                    {"id": "C_NO_POSITION", "kind": "CITY", "name": "没有任何位置的目的地"}
                  ]
                }
                """;

        CampusDataLoader loader = loader(createFixtureDataRoot(tempDir),
                new ByteArrayResource(applicationJson.getBytes(StandardCharsets.UTF_8)));
        loader.load();

        assertThat(loader.isLoaded()).isFalse();
        assertThat(loader.loadErrorMessage())
                .contains("C_NO_POSITION")
                .contains("缺少 displayPosition 与 routingPosition");
    }

    // ------------------------------------------------------------------

    private static CampusDataLoader projectLoader() {
        return loader(TestDataRoot.repoRoot(), applicationResource());
    }

    private static CampusDataLoader loader(Path dataRoot, Resource application) {
        CampusDataLoader loader = new CampusDataLoader(dataRoot.toString(), application, new ObjectMapper());
        loader.load();
        return loader;
    }

    private static Resource applicationResource() {
        return new ClassPathResource("data/application-places.json");
    }

    private static Resource fixtureApplicationResource() {
        return new ByteArrayResource(FIXTURE_APPLICATION_JSON.getBytes(StandardCharsets.UTF_8));
    }

    private static Resource displayOnlyApplicationResource() {
        return new ByteArrayResource(FIXTURE_DISPLAY_ONLY_APPLICATION_JSON.getBytes(StandardCharsets.UTF_8));
    }

    private static Path createFixtureDataRoot(Path tempDir) throws IOException {
        Path dataRoot = tempDir.resolve("repo");
        Path cleaned = dataRoot.resolve(Path.of("source-data", "校园数据", "cleaned"));
        Path webData = dataRoot.resolve(Path.of("web", "data"));
        Files.createDirectories(cleaned);
        Files.createDirectories(webData);
        Files.writeString(cleaned.resolve("official_places.json"), FIXTURE_OFFICIAL_PLACES, StandardCharsets.UTF_8);
        Files.writeString(cleaned.resolve("place_aliases.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(
                cleaned.resolve("mvp_seed_candidates.json"), FIXTURE_SEED_CANDIDATES, StandardCharsets.UTF_8);
        Files.writeString(webData.resolve("campus.json"), FIXTURE_CAMPUS_JSON, StandardCharsets.UTF_8);
        return dataRoot;
    }
}
