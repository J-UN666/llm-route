package cn.swjtu.zhixing.campus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 使用真实项目数据验证地点搜索、别名消歧、同名候选与不可算路原因。 */
class PlaceServiceTests {

    private static CampusDataLoader loader;
    private static PlaceService placeService;

    @BeforeAll
    static void setUp() {
        loader = new CampusDataLoader(
                TestDataRoot.repoRoot().toString(),
                new ClassPathResource("data/application-places.json"),
                new ObjectMapper());
        loader.load();
        placeService = new PlaceService(loader);
        assertThat(loader.isLoaded()).as("测试数据必须能加载：%s", loader.loadErrorMessage()).isTrue();
    }

    @Test
    void officialNameAndVerifiedAliasesResolveToSameId() {
        List<PlaceCandidate> byName = placeService.search("图书馆", PlaceKind.CAMPUS);
        assertThat(byName).isNotEmpty();
        assertThat(byName.get(0).id()).isEqualTo("swjtu_org_342");

        List<PlaceCandidate> byAlias = placeService.search("交大图书馆", PlaceKind.CAMPUS);
        assertThat(byAlias.get(0).id()).isEqualTo("swjtu_org_342");

        assertThat(placeService.search("犀浦校区图书馆", PlaceKind.CAMPUS))
                .anyMatch(candidate -> candidate.id().equals("swjtu_org_342"));
        assertThat(placeService.search("1教", PlaceKind.CAMPUS).get(0).id()).isEqualTo("swjtu_org_88");
    }

    @Test
    void eastGateAliasesResolveToSingleGateEntity() {
        assertThat(placeService.search("东门", PlaceKind.GATE))
                .extracting(PlaceCandidate::id)
                .containsExactly("campus_gate_east");
        assertThat(placeService.search("东大门", PlaceKind.GATE))
                .extracting(PlaceCandidate::id)
                .containsExactly("campus_gate_east");

        PlaceCandidate gate = placeService.getById("campus_gate_east");
        assertThat(gate.routingReady()).isTrue();
        assertThat(gate.routingPosition()).isNotNull();
        assertThat(gate.routingPosition().pointId()).isEqualTo("osm_node_13469658219");
        assertThat(gate.routingPosition().coordinateSystem()).isEqualTo("WGS84");
        assertThat(gate.routingPosition().positionRole()).isEqualTo("gate_exit");
        assertThat(gate.verificationStatus()).isEqualTo("APPLICATION_VERIFIED");
        assertThat(gate.sourceRefs()).contains("S01", "S04", "web/data/campus.json");
    }

    @Test
    void unknownKeywordReturnsEmptyCandidateList() {
        assertThat(placeService.search("不存在的地点名称xyz", PlaceKind.CAMPUS)).isEmpty();
        assertThat(placeService.search("zzz", (PlaceKind) null)).isEmpty();
        assertThat(placeService.search("东门", PlaceKind.CITY)).isEmpty();
    }

    @Test
    void sameNameCandidatesKeepDistinctIds() {
        List<PlaceCandidate> hits = placeService.search("乒乓球场", PlaceKind.CAMPUS);
        List<PlaceCandidate> exactlySameName = hits.stream()
                .filter(candidate -> candidate.name().equals("乒乓球场"))
                .toList();
        assertThat(exactlySameName).hasSize(3);
        assertThat(exactlySameName)
                .extracting(PlaceCandidate::id)
                .containsExactlyInAnyOrder("swjtu_point_127", "swjtu_point_128", "swjtu_point_129");

        assertThat(placeService.search("千益眼镜", PlaceKind.CAMPUS))
                .extracting(PlaceCandidate::id)
                .containsExactlyInAnyOrder("swjtu_point_102", "swjtu_point_103", "swjtu_point_104");
    }

    @Test
    void officialPlaceWithAmapPositionKeepsIdAndUnavailableReason() {
        PlaceCandidate place = placeService.getById("swjtu_org_100");

        assertThat(place.name()).isEqualTo("材料学院实验教学中心");
        assertThat(place.kind()).isEqualTo(PlaceKind.CAMPUS);
        assertThat(place.category()).isEqualTo("teaching_or_lab");
        assertThat(place.routingReady()).isFalse();
        assertThat(place.routingPosition()).isNull();
        assertThat(place.unavailableReason()).contains("暂不可算路");
        assertThat(place.displayPosition()).isNotNull();
        assertThat(place.displayPosition().coordinateSystem()).isEqualTo("GCJ-02");
        assertThat(place.displayPosition().positionRole()).isEqualTo("amap_building_poi");
        assertThat(place.displayPosition().longitude()).isEqualTo(103.983688);
        assertThat(place.displayPosition().latitude()).isEqualTo(30.770473);
        assertThat(place.verificationStatus()).isEqualTo("DISPLAY_POSITION_VERIFIED");
        assertThat(place.sourceRefs()).contains("S03");
        assertThat(place.dataVersion()).isEqualTo(loader.dataVersion());
    }

    @Test
    void teamConfirmedOriginKeepsAmapDisplaySeparateFromRoutingPoint() {
        PlaceCandidate seed = placeService.getById("osm_way_1098786977");
        assertThat(seed.kind()).isEqualTo(PlaceKind.CAMPUS);
        assertThat(seed.aliases()).contains("图书馆");
        assertThat(seed.routingReady()).isTrue();
        assertThat(seed.routingPosition().pointId()).isEqualTo("osm_node_5167087319");
        assertThat(seed.routingPosition().positionRole()).isEqualTo("building_external_access_point");
        assertThat(seed.routingPosition().longitude()).isEqualTo(103.9832456);
        assertThat(seed.unavailableReason()).isNull();
        assertThat(seed.verificationStatus()).isEqualTo("APPLICATION_VERIFIED");
        assertThat(seed.sourceRefs()).contains("TEAM_CONFIRMATION_2026_11_15", "DEMO_WALKWAY_DEFAULT_OPEN");
        assertThat(seed.displayPosition().pointId()).isEqualTo("amap_poi_B001C8P5UM");
        assertThat(seed.displayPosition().positionRole()).isEqualTo("amap_building_poi");
        assertThat(seed.displayPosition().coordinateSystem()).isEqualTo("GCJ-02");
    }

    @Test
    void westGateKeepsCampusOnlyLimitation() {
        PlaceCandidate west = placeService.getById("campus_gate_west");
        assertThat(west.kind()).isEqualTo(PlaceKind.GATE);
        assertThat(west.routingReady()).isFalse();
        assertThat(west.routingPosition()).isNull();
        assertThat(west.unavailableReason()).contains("campusOnly");
    }

    @Test
    void amapLocationsFillOnlyTheDisplayPositionOfExistingPlaces() {
        Map<String, String> poiIds = Map.of(
                "campus_gate_west", "B001C80PYA",
                "osm_node_2178309445", "B0FFG092LP",
                "swjtu_org_99", "B0FFFZSZYO",
                "osm_way_207570532", "B001C0A25F",
                "swjtu_org_345", "B001C0A25F",
                "swjtu_org_109", "B0FFH9BSNO",
                "swjtu_point_1018", "BV09337820",
                "swjtu_org_342", "B001C8P5UM",
                "swjtu_org_100", "B001C8N5I4",
                "osm_way_413859294", "B001C8MEK5");
        poiIds.forEach((placeId, poiId) -> {
            PlaceCandidate place = placeService.getById(placeId);
            assertThat(place.verificationStatus()).isEqualTo("DISPLAY_POSITION_VERIFIED");
            assertThat(place.displayPosition().coordinateSystem()).isEqualTo("GCJ-02");
            assertThat(place.displayPosition().pointId()).isEqualTo("amap_poi_" + poiId);
            assertThat(place.sourceRefs()).anyMatch(ref -> ref.endsWith("/place/" + poiId));
            assertThat(place.routingPosition()).isNull();
            assertThat(place.routingReady()).isFalse();
        });
        GeoPosition west = placeService.getById("campus_gate_west").displayPosition();
        assertThat(west.longitude()).isEqualTo(103.981417);
        assertThat(west.latitude()).isEqualTo(30.761769);
    }

    @Test
    void cityDestinationsAreRoutableWithVerifiedCoordinates() {
        List<PlaceCandidate> cities = placeService.search(null, PlaceKind.CITY);
        assertThat(cities).extracting(PlaceCandidate::id)
                .containsExactlyInAnyOrder("C01", "C02", "C03", "C04", "C08");
        assertThat(cities).allMatch(candidate -> candidate.routingReady());

        PlaceCandidate destination = placeService.getById("C01");
        assertThat(destination.name()).isEqualTo("天府广场");
        assertThat(destination.kind()).isEqualTo(PlaceKind.CITY);
        assertThat(destination.routingPosition()).isNotNull();
        assertThat(destination.routingPosition().coordinateSystem()).isEqualTo("WGS84");
        assertThat(destination.routingPosition().positionRole()).isEqualTo("destination_point");
        assertThat(destination.displayPosition()).isEqualTo(destination.routingPosition());
        assertThat(destination.verificationStatus()).isEqualTo("APPLICATION_VERIFIED");
        assertThat(destination.sourceRefs()).contains("web/data/city.json");
    }

    @Test
    void routingStateInvariantsHoldForAllCandidates() {
        for (PlaceCandidate candidate : loader.candidates()) {
            assertThat(candidate.routingReady())
                    .as("routingReady 必须由 routingPosition 派生：%s", candidate.id())
                    .isEqualTo(candidate.routingPosition() != null);
            if (candidate.routingPosition() != null) {
                assertThat(candidate.unavailableReason()).isNull();
            } else {
                assertThat(candidate.unavailableReason()).as("空位置必须保留原因：%s", candidate.id()).isNotBlank();
            }
            assertThat(candidate.id()).isNotBlank();
            assertThat(candidate.name()).isNotBlank();
            assertThat(candidate.dataVersion()).isEqualTo(loader.dataVersion());
        }
    }

    @Test
    void blankKeywordListsCandidatesInScope() {
        assertThat(placeService.search("", PlaceKind.GATE)).hasSize(6);
        assertThat(placeService.search(null, PlaceKind.CITY)).hasSize(5);
        assertThat(placeService.search("  ", (PlaceKind) null)).hasSize(220);
        assertThat(placeService.search("", PlaceKind.CAMPUS)).hasSize(209);
    }

    @Test
    void scopeParsingRejectsUnknownValues() {
        assertThat(PlaceKind.fromScope(null)).isNull();
        assertThat(PlaceKind.fromScope(" campus ")).isEqualTo(PlaceKind.CAMPUS);
        assertThat(placeService.search("图书馆", "GATE")).allMatch(candidate -> candidate.kind() == PlaceKind.GATE);
        assertThatThrownBy(() -> placeService.search("图书馆", "FOO"))
                .isInstanceOf(InvalidScopeException.class)
                .hasMessageContaining("不支持的 scope: FOO");
    }

    @Test
    void unknownIdThrowsPlaceNotFound() {
        assertThatThrownBy(() -> placeService.getById("unknown_place_id"))
                .isInstanceOf(PlaceNotFoundException.class)
                .hasMessageContaining("unknown_place_id");
        assertThatThrownBy(() -> placeService.getById("  "))
                .isInstanceOf(PlaceNotFoundException.class);
    }
}
