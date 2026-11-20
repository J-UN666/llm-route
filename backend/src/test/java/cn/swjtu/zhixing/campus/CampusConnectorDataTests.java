package cn.swjtu.zhixing.campus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 校验 W1-05 应用层接驳 JSON 自身的不变量，并与 S04 路网节点交叉核对（验收 A/B）。
 *
 * <p>不启动 Spring：直接读 classpath 数据与 source-data 中的 graph 节点，任何 0/缺失值都按错误处理，
 * 避免“脚本正常退出”代替行为验收。</p>
 */
class CampusConnectorDataTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private static final String WALKING_SPEED_BASIS = "1.33 m/s";

    private static final List<String> EXPECTED_CONNECTOR_IDS = List.of(
            "connector_library_east_gate", "connector_dorm1_east_gate",
            "connector_library_south_gate", "connector_library_northwest_gate", "connector_library_wind_tunnel_gate",
            "connector_library_southwest_gate", "connector_library_west_gate",
            "connector_dorm1_south_gate", "connector_dorm1_northwest_gate", "connector_dorm1_wind_tunnel_gate",
            "connector_dorm1_southwest_gate", "connector_dorm1_west_gate");
    /** 用户确认通行并按演示规则审核可用的两条接驳。 */
    private static final List<String> APPROVED_DEMO_CONNECTOR_IDS = List.of(
            "connector_library_east_gate",
            "connector_dorm1_east_gate");

    private static JsonNode root;
    private static Map<String, JsonNode> connectorsById;
    private static Map<String, JsonNode> graphNodesById;

    @BeforeAll
    static void loadData() throws Exception {
        try (InputStream in = new ClassPathResource("data/campus-connectors.json").getInputStream()) {
            root = MAPPER.readTree(in);
        }
        connectorsById = new LinkedHashMap<>();
        for (JsonNode connector : root.get("connectors")) {
            connectorsById.put(connector.get("id").asText(), connector);
        }

        Path graphNodesPath = TestDataRoot.repoRoot()
                .resolve(Path.of("source-data", "校园数据", "cleaned", "graph_nodes_candidates.json"));
        graphNodesById = new HashMap<>();
        for (JsonNode node : MAPPER.readTree(Files.readString(graphNodesPath))) {
            graphNodesById.put(node.get("node_id").asText(), node);
        }
        assertThat(graphNodesById).hasSizeGreaterThan(1200);
    }

    @Test
    void topLevelDataVersionAndConnectorIdsAreStable() {
        assertThat(root.get("dataVersion").asText()).isEqualTo("2026-11-20.1");
        assertThat(connectorsById.keySet()).containsExactlyElementsOf(EXPECTED_CONNECTOR_IDS);
        // 重启读取同版 JSON 得到相同 ID 与版本：直接重读一次比较。
        assertThat(connectorsById.keySet()).containsExactlyElementsOf(loadConnectorIdsAgain());
    }

    @Test
    void atLeastTwoOriginsHaveTeamConfirmedDemoConnectors() {
        List<String> origins = new ArrayList<>();
        for (String id : APPROVED_DEMO_CONNECTOR_IDS) {
            JsonNode connector = connectorsById.get(id);
            assertThat(connector.get("verificationStatus").asText()).isEqualTo("APPLICATION_VERIFIED");
            assertThat(connector.get("verifiedAt").asText()).isEqualTo("2026-11-15");
            assertThat(connector.get("verificationMethod").asText()).contains("团队确认", "地图路网估算", "楼外");
            assertThat(connector.get("sourceRefs").toString())
                    .contains("TEAM_CONFIRMATION_2026_11_15", "DEMO_WALKWAY_DEFAULT_OPEN");
            assertThat(connector.get("originPoint").get("positionRole").asText())
                    .isEqualTo("building_external_access_point");
            assertThat(connector.get("missingEvidence")).isEmpty();
            assertThat(connector.get("unavailableReasonCode").isNull()).isTrue();
            assertThat(connector.get("gateId").asText()).isEqualTo("campus_gate_east");
            assertThat(connector.get("segments")).isNotEmpty();
            assertThat(connector.get("walkMeters").asDouble()).isPositive();
            assertThat(connector.get("durationSeconds").asInt()).isPositive();
            origins.add(connector.get("originId").asText());
        }
        assertThat(origins).containsExactly("osm_way_1098786977", "osm_way_1099340057");
    }

    @Test
    void confirmedAndTrialConnectorsHaveDistinctEvidence() {
        int approved = 0;
        for (JsonNode connector : root.get("connectors")) {
            if ("APPLICATION_VERIFIED".equals(connector.get("verificationStatus").asText())) {
                approved++;
                assertThat(connector.get("missingEvidence")).isEmpty();
                assertThat(connector.get("verificationBasis")).isNotEmpty();
                assertThat(connector.get("sourceRefs").toString()).contains("TEAM_CONFIRMATION_2026_11_15");
            }
        }
        assertThat(approved).isEqualTo(2);
        assertThat(connectorsById.get("connector_library_west_gate").get("verificationStatus").asText())
                .isEqualTo("PROJECT_ASSUMED_OPEN");
        assertThat(connectorsById.get("connector_dorm1_southwest_gate").get("verificationStatus").asText())
                .isEqualTo("PROJECT_ASSUMED_OPEN");
    }

    @Test
    void segmentSumsMatchTotals() {
        for (String id : EXPECTED_CONNECTOR_IDS) {
            JsonNode connector = connectorsById.get(id);
            double meters = 0;
            int seconds = 0;
            for (JsonNode segment : connector.get("segments")) {
                meters += segment.get("lengthMeters").asDouble();
                seconds += segment.get("durationSeconds").asInt();
            }
            // 总米数必须严格等于写入文件的分段米数之和（分段各保留 2 位小数），仅允许浮点表示误差。
            assertThat(connector.get("walkMeters").asDouble())
                    .as(id + " 的 walkMeters 必须等于分段加总")
                    .isEqualTo(Math.round(meters * 100) / 100.0);
            assertThat(connector.get("durationSeconds").asInt())
                    .as(id + " 的 durationSeconds 必须等于分段加总")
                    .isEqualTo(seconds);
            assertThat(connector.get("durationBasis").asText())
                    .contains(WALKING_SPEED_BASIS)
                    .contains("各段之和");
        }
    }

    @Test
    void geometryEndpointsMatchOriginGateExitAndGraphNodes() {
        for (String id : EXPECTED_CONNECTOR_IDS) {
            JsonNode connector = connectorsById.get(id);
            JsonNode originPoint = connector.get("originPoint");
            JsonNode gateExitPoint = connector.get("gateExitPoint");
            JsonNode segments = connector.get("segments");
            assertThat(segments).isNotEmpty();

            JsonNode firstGeometry = segments.get(0).get("geometry");
            assertThat(firstGeometry.get("coordinateSystem").asText()).isEqualTo("WGS84");
            assertThat(firstGeometry.get("points")).isNotEmpty();
            assertCoordinate(firstGeometry.get("points").get(0), originPoint, id + " 首段几何起点与 originPoint");

            JsonNode lastSegment = segments.get(segments.size() - 1);
            JsonNode lastPoints = lastSegment.get("geometry").get("points");
            assertCoordinate(lastPoints.get(lastPoints.size() - 1), gateExitPoint,
                    id + " 末段几何终点与 gateExitPoint");

            for (int index = 0; index < segments.size(); index++) {
                JsonNode segment = segments.get(index);
                String fromId = segment.get("fromPointId").asText();
                String toId = segment.get("toPointId").asText();
                JsonNode fromNode = graphNodesById.get(fromId);
                JsonNode toNode = graphNodesById.get(toId);
                assertThat(fromNode).as(id + " 分段起点必须存在于 S04 路网节点：" + fromId).isNotNull();
                assertThat(toNode).as(id + " 分段终点必须存在于 S04 路网节点：" + toId).isNotNull();

                JsonNode points = segment.get("geometry").get("points");
                assertCoordinate(points.get(0), fromNode, id + " 分段几何起点与路网节点 " + fromId);
                assertCoordinate(points.get(points.size() - 1), toNode,
                        id + " 分段几何终点与路网节点 " + toId);

                if (index > 0) {
                    JsonNode previousPoints = segments.get(index - 1).get("geometry").get("points");
                    assertSameCoordinate(previousPoints.get(previousPoints.size() - 1), points.get(0),
                            id + " 分段几何连续性");
                }
            }

            assertNodeCoordinate(originPoint, graphNodesById.get(originPoint.get("pointId").asText()),
                    id + " originPoint 与路网节点");
            assertNodeCoordinate(gateExitPoint, graphNodesById.get(gateExitPoint.get("pointId").asText()),
                    id + " gateExitPoint 与路网节点");
        }
    }

    @Test
    void segmentGeometryLengthsMatchStatedMeters() {
        for (String id : EXPECTED_CONNECTOR_IDS) {
            for (JsonNode segment : connectorsById.get(id).get("segments")) {
                JsonNode points = segment.get("geometry").get("points");
                double meters = 0;
                for (int index = 1; index < points.size(); index++) {
                    meters += haversine(points.get(index - 1), points.get(index));
                }
                assertThat(meters)
                        .as(id + " 的 " + segment.get("segmentId").asText() + " 几何长度与 lengthMeters 不一致")
                        .isCloseTo(segment.get("lengthMeters").asDouble(), within(0.1));
            }
        }
    }

    @Test
    void gateExitPointIsTheOutboundPhysicalGatePoint() {
        for (String id : EXPECTED_CONNECTOR_IDS) {
            JsonNode connector = connectorsById.get(id);
            JsonNode gateExitPoint = connector.get("gateExitPoint");
            String exitPointId = gateExitPoint.get("pointId").asText();
            boolean foundOutbound = false;
            for (JsonNode gatePoint : connector.get("gatePoints")) {
                if (!gatePoint.get("pointId").asText().equals(exitPointId)) {
                    continue;
                }
                assertThat(gatePoint.get("direction").asText()).isEqualTo("OUTBOUND");
                assertThat(gatePoint.get("coordinateSystem").asText()).isEqualTo("WGS84");
                foundOutbound = true;
            }
            assertThat(foundOutbound)
                    .as(id + " 的 gateExitPoint 必须出现在 gatePoints 的 OUTBOUND 物理点位中")
                    .isTrue();
        }
        // 东门保留进/出两个方向的物理点位，接驳终点取实际出校方向。
        JsonNode gatePoints = connectorsById.get("connector_library_east_gate").get("gatePoints");
        Map<String, String> directionByPoint = new HashMap<>();
        for (JsonNode gatePoint : gatePoints) {
            directionByPoint.put(gatePoint.get("pointId").asText(), gatePoint.get("direction").asText());
        }
        assertThat(directionByPoint)
                .containsEntry("osm_node_4150911420", "INBOUND")
                .containsEntry("osm_node_13469658219", "OUTBOUND");
    }

    @Test
    void demoPassabilityAndSourceLimitationsAreExplicit() {
        for (String id : APPROVED_DEMO_CONNECTOR_IDS) {
            JsonNode connector = connectorsById.get(id);
            assertThat(connector.get("durationBasis").asText()).contains("估算", "现场");
            JsonNode rules = connector.get("accessRules");
            assertThat(rules.get("pedestrianAccess").asText()).isEqualTo("PERMITTED");
            assertThat(rules.get("gateOpenHoursStatus").asText()).isEqualTo("PROJECT_ASSUMED_UNRESTRICTED");
            assertThat(rules.get("timeRestrictions")).isEmpty();
            assertThat(rules.get("timeRestrictionBasis").asText()).contains("项目", "用户");
            for (JsonNode segment : connector.get("segments")) {
                assertThat(segment.get("passabilityStatus").asText()).isEqualTo("OPEN");
                assertThat(segment.get("passabilityNote").asText()).contains("OSM", "团队确认", "估算");
                assertThat(segment.get("sourceRefs").toString()).contains("S04", "TEAM_CONFIRMATION_2026_11_15");
                assertThat(segment.get("geometry").get("coordinateSystem").asText()).isEqualTo("WGS84");
            }
        }
    }

    @Test
    void trialConnectorsRetainMissingEvidenceAndExcludeProhibitedSegments() {
        for (String id : EXPECTED_CONNECTOR_IDS) {
            JsonNode connector = connectorsById.get(id);
            if (APPROVED_DEMO_CONNECTOR_IDS.contains(id)) {
                continue;
            }
            assertThat(connector.get("verificationStatus").asText()).isEqualTo("PROJECT_ASSUMED_OPEN");
            assertThat(connector.get("missingEvidence")).isNotEmpty();
            assertThat(connector.get("sourceRefs").toString()).contains("PROJECT_ALL_GATES_TRIAL_2026_11_20");
            assertThat(connector.get("unavailableReasonCode").isNull()).isTrue();
            assertThat(connector.get("accessRules").get("pedestrianAccess").asText()).isEqualTo("ASSUMED_OPEN");
            for (JsonNode segment : connector.get("segments")) {
                assertThat(segment.get("passabilityStatus").asText()).isEqualTo("ASSUMED_OPEN");
            }
        }
        assertThat(connectorsById.get("connector_dorm1_southwest_gate").get("verificationBasis").toString())
                .contains("locked=yes");
    }

    @Test
    void walkingSpeedEstimateIsSaneAndMatchesBasis() {
        for (String id : EXPECTED_CONNECTOR_IDS) {
            JsonNode connector = connectorsById.get(id);
            double speed = connector.get("walkMeters").asDouble() / connector.get("durationSeconds").asInt();
            assertThat(speed).as(id + " 的估算步行速度").isBetween(1.2, 1.45);
        }
        // W1-08 验收样例的 800m/600s 口径为 1.33 m/s；图书馆→东门 879m 的估算秒数应接近 879/1.33≈661s。
        int librarySeconds = connectorsById.get("connector_library_east_gate").get("durationSeconds").asInt();
        assertThat(librarySeconds).isBetween(650, 680);
    }

    // ------------------------------------------------------------------

    private static List<String> loadConnectorIdsAgain() {
        List<String> ids = new ArrayList<>();
        try (InputStream in = new ClassPathResource("data/campus-connectors.json").getInputStream()) {
            JsonNode again = MAPPER.readTree(in);
            assertThat(again.get("dataVersion").asText()).isEqualTo(root.get("dataVersion").asText());
            for (JsonNode connector : again.get("connectors")) {
                ids.add(connector.get("id").asText());
            }
        } catch (Exception ex) {
            throw new IllegalStateException("重读接驳应用数据失败", ex);
        }
        return ids;
    }

    private static void assertCoordinate(JsonNode actual, JsonNode expected, String label) {
        assertThat(actual).as(label + " 缺失坐标").isNotNull();
        assertThat(actual.get(0).asDouble()).as(label + " 经度").isCloseTo(expected.get("longitude").asDouble(), within(1e-7));
        assertThat(actual.get(1).asDouble()).as(label + " 纬度").isCloseTo(expected.get("latitude").asDouble(), within(1e-7));
    }

    private static void assertNodeCoordinate(JsonNode actualPoint, JsonNode expectedNode, String label) {
        assertThat(actualPoint).as(label + " 缺失点位").isNotNull();
        assertThat(expectedNode).as(label + " 在 S04 路网节点中不存在").isNotNull();
        assertThat(actualPoint.get("longitude").asDouble()).as(label + " 经度")
                .isCloseTo(expectedNode.get("longitude").asDouble(), within(1e-7));
        assertThat(actualPoint.get("latitude").asDouble()).as(label + " 纬度")
                .isCloseTo(expectedNode.get("latitude").asDouble(), within(1e-7));
    }

    private static void assertSameCoordinate(JsonNode actual, JsonNode expected, String label) {
        assertThat(actual).as(label + " 缺失坐标").isNotNull();
        assertThat(actual.get(0).asDouble()).as(label + " 经度").isCloseTo(expected.get(0).asDouble(), within(1e-9));
        assertThat(actual.get(1).asDouble()).as(label + " 纬度").isCloseTo(expected.get(1).asDouble(), within(1e-9));
    }

    private static double haversine(JsonNode first, JsonNode second) {
        double lat1 = Math.toRadians(first.get(1).asDouble());
        double lat2 = Math.toRadians(second.get(1).asDouble());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(second.get(0).asDouble() - first.get(0).asDouble());
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }
}
