package cn.swjtu.zhixing.campus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 团队确认的演示接驳：楼外接入点、人行道默认开放，明确禁行仍排除。 */
@SpringBootTest(properties = "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute")
class CampusConnectorServiceTests {

    private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-11-15T09:00:00+08:00");

    @DynamicPropertySource
    static void campusDataRoot(DynamicPropertyRegistry registry) {
        registry.add("campus.data-root", () -> TestDataRoot.repoRoot().toString());
    }

    @Autowired
    private CampusConnectorService connectorService;
    @Autowired
    private PlaceService placeService;

    @Test
    void libraryReturnsTeamConfirmedDemoConnector() {
        CampusConnectorService.Resolution resolution = connectorService.resolve("osm_way_1098786977", DEPARTURE);
        assertThat(resolution.available()).hasSize(1);
        CampusConnector connector = resolution.available().get(0);
        assertThat(connector.id()).isEqualTo("connector_library_east_gate");
        assertThat(connector.verificationStatus()).isEqualTo("APPLICATION_VERIFIED");
        assertThat(connector.verificationMethod()).contains("团队确认", "地图路网估算");
        assertThat(connector.originPoint().positionRole()).isEqualTo("building_external_access_point");
        assertThat(connector.gateExitPoint().pointId()).isEqualTo("osm_node_13469658219");
        assertThat(connector.walkMeters()).isEqualTo(879.07);
        assertThat(connector.durationSeconds()).isEqualTo(664);
        assertThat(connector.missingEvidence()).isEmpty();
        assertThat(resolution.unavailable()).hasSize(1);
        assertThat(resolution.unavailable().get(0).reasonCode()).isEqualTo("GATE_EXIT_POINT_UNVERIFIED");
    }

    @Test
    void dormitoryReturnsTeamConfirmedDemoConnector() {
        CampusConnectorService.Resolution resolution = connectorService.resolve("osm_way_1099340057", DEPARTURE);
        assertThat(resolution.available()).hasSize(1);
        CampusConnector connector = resolution.available().get(0);
        assertThat(connector.id()).isEqualTo("connector_dorm1_east_gate");
        assertThat(connector.verificationStatus()).isEqualTo("APPLICATION_VERIFIED");
        assertThat(connector.walkMeters()).isEqualTo(1807.87);
        assertThat(connector.durationSeconds()).isEqualTo(1369);
        assertThat(resolution.unavailable()).hasSize(1);
        assertThat(resolution.unavailable().get(0).reasonCode()).isEqualTo("GATE_LOCKED");
    }

    @Test
    void unknownOriginThrowsPlaceNotFound() {
        assertThatThrownBy(() -> connectorService.getAvailableConnectors("unknown_origin_id", DEPARTURE))
                .isInstanceOf(PlaceNotFoundException.class);
    }

    @Test
    void gateOriginHasNoCampusConnector() {
        CampusConnectorService.Resolution resolution = connectorService.resolve("campus_gate_east", DEPARTURE);
        assertThat(resolution.originKind()).isEqualTo("GATE");
        assertThat(resolution.available()).isEmpty();
    }

    @Test
    void cityOriginHasNoCampusConnector() {
        CampusConnectorService.Resolution resolution = connectorService.resolve("C01", DEPARTURE);
        assertThat(resolution.originKind()).isEqualTo("CITY");
        assertThat(resolution.available()).isEmpty();
    }

    @Test
    void departureTimeIsInterpretedInAsiaShanghaiRegardlessOfOffsetForm() {
        assertThat(idsAt("2026-11-15T09:00:00+08:00"))
                .containsExactly("connector_library_east_gate");
        assertThat(idsAt("2026-11-15T01:00:00Z"))
                .isEqualTo(idsAt("2026-11-15T09:00:00+08:00"));
    }

    @Test
    void approvedEastGateConnectorsAreUnrestrictedByTime() {
        for (String origin : List.of("osm_way_1098786977", "osm_way_1099340057")) {
            for (String time : List.of("2026-11-19T23:55:00+08:00", "2026-11-20T00:05:00+08:00")) {
                List<CampusConnector> connectors = connectorService.getAvailableConnectors(origin, OffsetDateTime.parse(time));
                assertThat(connectors).singleElement().satisfies(connector -> {
                    assertThat(connector.gateExitPoint().pointId()).isEqualTo("osm_node_13469658219");
                    assertThat(connector.accessRules().gateOpenHoursStatus()).isEqualTo("PROJECT_ASSUMED_UNRESTRICTED");
                    assertThat(connector.accessRules().pedestrianAccess()).isEqualTo("PERMITTED");
                    assertThat(connector.accessRules().timeRestrictions()).isEmpty();
                });
            }
        }
    }

    @Test
    void getAvailableConnectorsMatchesResolveAvailable() {
        CampusConnectorService.Resolution resolution = connectorService.resolve("osm_way_1098786977", DEPARTURE);
        assertThat(resolution.originId()).isEqualTo("osm_way_1098786977");
        assertThat(resolution.originKind()).isEqualTo("CAMPUS");
        assertThat(resolution.dataVersion()).isEqualTo("2026-11-19.2");
        assertThat(resolution.available()).hasSize(1);
        assertThat(connectorService.getAvailableConnectors("osm_way_1098786977", DEPARTURE))
                .isEqualTo(resolution.available());
    }

    @Test
    void serviceReloadsSameVersionAndIdsAsPlaceDataBuild() {
        assertThat(connectorService.isLoaded()).isTrue();
        assertThat(connectorService.loadErrorMessage()).isNull();
        assertThat(connectorService.dataVersion()).isEqualTo("2026-11-19.2");
        // 与地点数据版本独立：接驳数据升级不应隐式改变地点数据版本。
        assertThat(placeService.dataVersion()).isEqualTo("2026-11-19.2");
    }

    private List<String> idsAt(String departure) {
        return connectorService.getAvailableConnectors("osm_way_1098786977", OffsetDateTime.parse(departure))
                .stream().map(CampusConnector::id).toList();
    }

    @Test
    void approvedOriginsAreSelectableAndUseTheSameRoutingPointAsConnectors() {
        for (String id : List.of("osm_way_1098786977", "osm_way_1099340057")) {
            PlaceCandidate place = placeService.getById(id);
            CampusConnector connector = connectorService.getAvailableConnectors(id, DEPARTURE).get(0);
            assertThat(place.routingReady()).isTrue();
            assertThat(place.routingPosition().pointId()).isEqualTo(connector.originPoint().pointId());
            assertThat(place.routingPosition().longitude()).isEqualTo(connector.originPoint().longitude());
            assertThat(place.routingPosition().latitude()).isEqualTo(connector.originPoint().latitude());
            assertThat(place.displayPosition().pointId()).isNotEqualTo(connector.originPoint().pointId());
        }
    }

    @Test
    void crossMidnightTimeRestrictionClosesOnlyWithinWindow() {
        CampusConnectorTimeRestriction nightly = new CampusConnectorTimeRestriction(
                List.of(), "23:00", "06:00", "夜间关闭");
        assertThat(CampusConnectorService.isClosed(nightly, DayOfWeek.SATURDAY, LocalTime.parse("23:30"))).isTrue();
        assertThat(CampusConnectorService.isClosed(nightly, DayOfWeek.SUNDAY, LocalTime.parse("05:30"))).isTrue();
        assertThat(CampusConnectorService.isClosed(nightly, DayOfWeek.SUNDAY, LocalTime.parse("06:00"))).isFalse();
        assertThat(CampusConnectorService.isClosed(nightly, DayOfWeek.SATURDAY, LocalTime.parse("22:59"))).isFalse();
    }

    @Test
    void daySpecificRestrictionAppliesOnlyOnConfiguredDays() {
        CampusConnectorTimeRestriction mondayNoon = new CampusConnectorTimeRestriction(
                List.of(DayOfWeek.MONDAY), "08:00", "12:00", "周一上午关闭");
        assertThat(CampusConnectorService.isClosed(mondayNoon, DayOfWeek.MONDAY, LocalTime.parse("09:00"))).isTrue();
        assertThat(CampusConnectorService.isClosed(mondayNoon, DayOfWeek.MONDAY, LocalTime.parse("12:00"))).isFalse();
        assertThat(CampusConnectorService.isClosed(mondayNoon, DayOfWeek.TUESDAY, LocalTime.parse("09:00"))).isFalse();
    }
}
