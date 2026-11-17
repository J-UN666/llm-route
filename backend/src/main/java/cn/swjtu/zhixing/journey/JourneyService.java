package cn.swjtu.zhixing.journey;

import cn.swjtu.zhixing.campus.CampusConnector;
import cn.swjtu.zhixing.campus.CampusConnectorDataLoadException;
import cn.swjtu.zhixing.campus.CampusConnectorGeometry;
import cn.swjtu.zhixing.campus.CampusConnectorPoint;
import cn.swjtu.zhixing.campus.CampusConnectorSegment;
import cn.swjtu.zhixing.campus.CampusConnectorService;
import cn.swjtu.zhixing.campus.GeoPosition;
import cn.swjtu.zhixing.campus.PlaceCandidate;
import cn.swjtu.zhixing.campus.PlaceDataLoadException;
import cn.swjtu.zhixing.campus.PlaceKind;
import cn.swjtu.zhixing.campus.PlaceNotFoundException;
import cn.swjtu.zhixing.campus.PlaceService;
import cn.swjtu.zhixing.transit.TransitErrorCode;
import cn.swjtu.zhixing.transit.TransitGeometry;
import cn.swjtu.zhixing.transit.TransitLine;
import cn.swjtu.zhixing.transit.TransitPlan;
import cn.swjtu.zhixing.transit.TransitPoint;
import cn.swjtu.zhixing.transit.TransitPreference;
import cn.swjtu.zhixing.transit.TransitProvider;
import cn.swjtu.zhixing.transit.TransitSearchResult;
import cn.swjtu.zhixing.transit.TransitSegment;
import cn.swjtu.zhixing.transit.TransitStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 普通行程组合服务：校园步行接驳 + 城市公共交通段 → {@link JourneyPlan}。
 *
 * <p>表单（W1-07）与后续模型（W1-11+）共用 {@link #plan(JourneyRequest)} 这一个入口，
 * 输入校验、请求标识、错误映射都放在这里，控制器只负责 HTTP 边界。</p>
 *
 * <p>组合口径（见 docs/api.md 与 W1-08 任务卡）：</p>
 * <ul>
 *   <li>城市段起点固定为接驳的实际出校点 {@code gateExitPoint}，与校园步行段终点同一点；</li>
 *   <li>{@code cityDepartureTime = 请求出发时刻 + 接驳 durationSeconds}；</li>
 *   <li>{@code providerDepartureTime} 使用城市提供方取整后的出发时刻，{@code roundingWaitSeconds}
 *       取其等待秒数；城市段耗时已包含到站步行，组合时只累加一次；</li>
 *   <li>{@code arrivalTime = providerDepartureTime + 城市段 durationSeconds}，
 *       {@code totalDurationSeconds = arrivalTime - departureTime}；</li>
 *   <li>费用未知、步行距离未知、几何缺失均保留 {@code null}（不填 0、不猜值）；</li>
 *   <li>多出校点部分城市段查询失败时只展示成功候选并写入 {@code warnings}，
 *       全部失败才返回对应错误状态。</li>
 *   <li>成功候选统一交给 {@link JourneyPlanRanking} 处理（W1-09）：按实际出校点与乘车段
 *       序列去重、按请求偏好排序（未知指标排在已知值之后）、最多返回三条；比较只针对
 *       本次查询实际获得的候选，不足三条时如实返回实际数量。</li>
 * </ul>
 */
@Service
public class JourneyService {

    private static final Logger log = LoggerFactory.getLogger(JourneyService.class);

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String CAMPUS_WALK = "CAMPUS_WALK";
    private static final String CAMPUS_COORDINATE_SYSTEM = "WGS84";
    private static final Set<String> SUPPORTED_COORDINATE_SYSTEMS =
            Set.of("WGS84", "WGS-84", "GCJ-02", "GCJ02");

    private final PlaceService placeService;
    private final CampusConnectorService connectorService;
    private final TransitProvider transitProvider;
    private final Clock clock;

    /** Spring 使用的构造器。 */
    @Autowired
    public JourneyService(PlaceService placeService,
                          CampusConnectorService connectorService,
                          TransitProvider transitProvider) {
        this(placeService, connectorService, transitProvider, Clock.systemUTC());
    }

    /** 可控时钟的测试构造器。 */
    public JourneyService(PlaceService placeService,
                          CampusConnectorService connectorService,
                          TransitProvider transitProvider,
                          Clock clock) {
        this.placeService = placeService;
        this.connectorService = connectorService;
        this.transitProvider = transitProvider;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 查询一次普通行程。校验失败抛 {@link JourneyApiException}（由控制器异常处理器转 HTTP 错误），
     * 有效但无候选返回 HTTP 200 语义的 {@link JourneyStatus#NO_ROUTE} 空方案列表。
     */
    public JourneyResponse plan(JourneyRequest request) {
        String requestId = JourneyIds.requestId();
        OffsetDateTime queriedAt = now();
        if (request == null) {
            throw new JourneyApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "请求体不能为空", false, requestId);
        }
        String originId = requireText(request.originId(), "起点 originId", requestId);
        String destinationId = requireText(request.destinationId(), "终点 destinationId", requestId);
        OffsetDateTime departure = parseDeparture(request.departureTime(), requestId);
        TransitPreference preference = parsePreference(request.preference(), requestId);

        PlaceCandidate origin = loadPlace(originId, "起点", requestId);
        PlaceCandidate destination = loadPlace(destinationId, "终点", requestId);
        if (origin.kind() != PlaceKind.CAMPUS) {
            throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CAMPUS_CONNECTOR_UNAVAILABLE",
                    "起点“" + origin.name() + "”不是校园内部地点（kind=" + origin.kind()
                            + "），普通行程只支持从校园内部出发",
                    false, requestId);
        }
        if (destination.kind() != PlaceKind.CITY) {
            throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLACE_UNAVAILABLE",
                    "终点“" + destination.name() + "”不是城市目的地（kind=" + destination.kind()
                            + "），当前只支持校园到城市方向的查询",
                    false, requestId);
        }
        GeoPosition originRouting = requireRoutingPosition(origin, "起点", requestId);
        GeoPosition destinationRouting = requireRoutingPosition(destination, "终点", requestId);
        validateCoordinateSystem(originRouting.coordinateSystem(), "起点算路位置", requestId);
        validateCoordinateSystem(destinationRouting.coordinateSystem(), "终点算路位置", requestId);
        TransitPoint destinationPoint = toTransitPoint(destinationRouting, "终点算路位置", requestId);

        CampusConnectorService.Resolution resolution = resolveConnectors(originId, departure, requestId);
        if (resolution.available().isEmpty()) {
            throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CAMPUS_CONNECTOR_UNAVAILABLE",
                    "起点“" + origin.name() + "”当前时刻没有可用校园接驳："
                            + unavailableSummary(resolution),
                    false, requestId);
        }

        String campusDataVersion = campusDataVersion(requestId);
        List<JourneyPlan> candidates = new ArrayList<>();
        List<ProviderFailure> failures = new ArrayList<>();
        boolean anySearchCompleted = false;
        String providerName = null;

        for (CampusConnector connector : resolution.available()) {
            validateConnector(connector, requestId);
            OffsetDateTime cityDeparture = departure.plusSeconds(connector.durationSeconds());
            TransitPoint gateExit = toTransitPoint(connector.gateExitPoint(),
                    "接驳“" + connector.id() + "”的出校点", requestId);

            TransitSearchResult result;
            try {
                result = transitProvider.search(gateExit, destinationPoint, cityDeparture, preference);
            } catch (RuntimeException ex) {
                log.warn("城市段查询异常 requestId={} connector={} gate={}",
                        requestId, connector.id(), connector.gateId(), ex);
                failures.add(new ProviderFailure(connector.gateId(), TransitErrorCode.PROVIDER_ERROR,
                        "城市段服务调用异常"));
                continue;
            }
            if (result == null) {
                failures.add(new ProviderFailure(connector.gateId(), TransitErrorCode.PROVIDER_ERROR,
                        "城市段服务未返回结果"));
                continue;
            }
            if (result.provider() != null && !result.provider().isBlank()) {
                providerName = result.provider();
            }
            if (result.errorCode() != null && result.errorCode() != TransitErrorCode.NO_ROUTE) {
                failures.add(new ProviderFailure(connector.gateId(), result.errorCode(),
                        safeMessage(result.errorMessage())));
                continue;
            }
            anySearchCompleted = true;
            if (result.status() == TransitStatus.NO_ROUTE || result.plans().isEmpty()) {
                continue;
            }
            for (TransitPlan transitPlan : result.plans()) {
                candidates.add(buildPlan(origin, destination, connector, result, transitPlan,
                        departure, queriedAt, campusDataVersion));
            }
        }

        if (!candidates.isEmpty()) {
            List<JourneyPlan> plans = JourneyPlanRanking.arrange(candidates, preference);
            List<String> warnings = successWarnings(failures);
            return new JourneyResponse(requestId, JourneyStatus.SUCCEEDED, plans, queriedAt,
                    campusDataVersion, providerName, warnings);
        }
        if (!anySearchCompleted && !failures.isEmpty()) {
            throw mapAllFailures(failures, requestId);
        }
        return new JourneyResponse(requestId, JourneyStatus.NO_ROUTE, List.of(), queriedAt,
                campusDataVersion, providerName, successWarnings(failures));
    }

    private JourneyPlan buildPlan(PlaceCandidate origin,
                                  PlaceCandidate destination,
                                  CampusConnector connector,
                                  TransitSearchResult result,
                                  TransitPlan transitPlan,
                                  OffsetDateTime departure,
                                  OffsetDateTime queriedAt,
                                  String campusDataVersion) {
        OffsetDateTime cityDeparture = departure.plusSeconds(connector.durationSeconds());
        OffsetDateTime providerDeparture = result.providerDepartureTime() == null
                ? cityDeparture.plusSeconds(Math.max(0, result.roundingWaitSeconds()))
                : result.providerDepartureTime().atZoneSameInstant(BUSINESS_ZONE).toOffsetDateTime();
        OffsetDateTime arrival = transitPlan.durationSeconds() == null
                ? null
                : providerDeparture.plusSeconds(transitPlan.durationSeconds());
        Integer totalDuration = arrival == null
                ? null
                : Math.toIntExact(Duration.between(departure, arrival).getSeconds());
        BigDecimal totalWalking = sumWalking(connector.walkMeters(), transitPlan.walkingMeters());

        List<JourneySegment> segments = new ArrayList<>();
        segments.add(campusSegment(origin, connector));
        int sequence = 2;
        for (TransitSegment segment : transitPlan.segments()) {
            segments.add(citySegment(segment, sequence));
            sequence++;
        }

        JourneyPoint exitPoint = gateExitPoint(connector);
        String planId = JourneyIds.planId(
                origin.id(),
                destination.id(),
                departure.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                connector.gateId(),
                JourneyPlanRanking.routeSignature(exitPoint, segments));

        return new JourneyPlan(
                planId,
                origin.id(),
                destination.id(),
                connector.gateId(),
                exitPoint,
                departure,
                cityDeparture,
                providerDeparture,
                Math.max(0, result.roundingWaitSeconds()),
                arrival,
                totalDuration,
                totalWalking,
                transitPlan.transferCount(),
                transitPlan.costYuan(),
                segments,
                sources(connector, transitPlan),
                queriedAt,
                campusDataVersion);
    }

    private JourneySegment campusSegment(PlaceCandidate origin, CampusConnector connector) {
        JourneyPoint from = new JourneyPoint(
                origin.routingPosition().pointId(),
                origin.name(),
                origin.routingPosition().longitude(),
                origin.routingPosition().latitude(),
                origin.routingPosition().coordinateSystem(),
                origin.routingPosition().positionRole());
        JourneyPoint to = new JourneyPoint(
                connector.gateExitPoint().pointId(),
                connector.gateName(),
                connector.gateExitPoint().longitude(),
                connector.gateExitPoint().latitude(),
                connector.gateExitPoint().coordinateSystem(),
                connector.gateExitPoint().positionRole());
        JourneyGeometry geometry = campusGeometry(connector);
        String coordinateSystem = geometry == null ? null : geometry.coordinateSystem();
        String description = "从" + origin.name() + "步行至" + connector.gateName() + "出校点";
        return new JourneySegment(1, CAMPUS_WALK, description, from, to,
                null, null, null, null, null,
                connector.walkMeters(), connector.durationSeconds(), geometry, coordinateSystem);
    }

    /** 合并接驳分段的 WGS84 折线；任一分段缺几何则整段不输出几何，避免出现断线。 */
    private JourneyGeometry campusGeometry(CampusConnector connector) {
        if (connector.segments() == null || connector.segments().isEmpty()) {
            return null;
        }
        List<List<Double>> merged = new ArrayList<>();
        for (CampusConnectorSegment segment : connector.segments()) {
            CampusConnectorGeometry geometry = segment.geometry();
            if (geometry == null || geometry.points() == null || geometry.points().isEmpty()) {
                return null;
            }
            for (List<Double> point : geometry.points()) {
                if (merged.isEmpty() || !merged.get(merged.size() - 1).equals(point)) {
                    merged.add(List.copyOf(point));
                }
            }
        }
        if (merged.size() < 2) {
            return null;
        }
        return new JourneyGeometry(JourneyGeometry.LINE_STRING, merged, CAMPUS_COORDINATE_SYSTEM);
    }

    private JourneySegment citySegment(TransitSegment segment, int sequence) {
        String mode = normalizeMode(segment.mode());
        TransitLine line = segment.lines() == null || segment.lines().isEmpty()
                ? null
                : segment.lines().get(0);
        String boarding = firstNonBlank(segment.boardingStation(),
                line == null ? null : line.boardingStation());
        String alighting = firstNonBlank(segment.alightingStation(),
                line == null ? null : line.alightingStation());
        TransitGeometry geometry = segment.geometry();
        JourneyGeometry journeyGeometry = geometry == null
                ? null
                : new JourneyGeometry(JourneyGeometry.LINE_STRING, geometry.points(),
                        geometry.coordinateSystem());
        JourneyPoint fromPoint = stationPoint(boarding, geometry, true);
        JourneyPoint toPoint = stationPoint(alighting, geometry, false);
        Integer distanceMeters = "WALK".equals(mode) && segment.walkingMeters() != null
                ? segment.walkingMeters()
                : null;
        String description = describeCitySegment(mode, line, boarding, alighting);
        return new JourneySegment(sequence, mode, description, fromPoint, toPoint,
                line == null ? null : line.lineId(),
                line == null ? null : line.name(),
                line == null ? null : line.direction(),
                boarding,
                alighting,
                distanceMeters == null ? null : distanceMeters.doubleValue(),
                segment.durationSeconds(),
                journeyGeometry,
                journeyGeometry == null ? null : journeyGeometry.coordinateSystem());
    }

    /**
     * 高德交通方式到普通行程 mode 的映射：地铁统一为 {@code METRO}；步行、公交保持原名；
     * 铁路、出租车等供应商实际返回的方式原样保留，避免丢失信息。
     */
    private String normalizeMode(String rawMode) {
        if (rawMode == null || rawMode.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = rawMode.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SUBWAY", "METRO" -> "METRO";
            case "WALK" -> "WALK";
            case "BUS" -> "BUS";
            default -> rawMode.trim();
        };
    }

    private String describeCitySegment(String mode, TransitLine line, String boarding, String alighting) {
        String stationPart = boarding != null && alighting != null
                ? boarding + " → " + alighting
                : boarding != null ? boarding : alighting;
        return switch (mode) {
            case "WALK" -> "步行" + (stationPart == null ? "" : "（" + stationPart + "）");
            case "BUS", "METRO" -> "乘坐" + (line == null || line.name() == null ? "公共交通" : line.name())
                    + (stationPart == null ? "" : "：" + stationPart);
            case "RAILWAY" -> "乘坐铁路" + (line == null || line.name() == null ? "" : line.name())
                    + (stationPart == null ? "" : "：" + stationPart);
            case "TAXI" -> "乘坐出租车" + (stationPart == null ? "" : "：" + stationPart);
            default -> "城市交通" + (stationPart == null ? "" : "：" + stationPart);
        };
    }

    /** 从分段几何取首/尾点作为端点；无几何时若已知站名则输出仅带名称的端点。 */
    private JourneyPoint stationPoint(String stationName, TransitGeometry geometry, boolean first) {
        if (geometry != null && geometry.points() != null && !geometry.points().isEmpty()) {
            List<Double> point = first ? geometry.points().get(0)
                    : geometry.points().get(geometry.points().size() - 1);
            Double longitude = point.size() >= 2 ? point.get(0) : null;
            Double latitude = point.size() >= 2 ? point.get(1) : null;
            return new JourneyPoint(null, stationName, longitude, latitude,
                    geometry.coordinateSystem(), null);
        }
        if (stationName == null) {
            return null;
        }
        return new JourneyPoint(null, stationName, null, null, null, null);
    }

    private List<String> sources(CampusConnector connector, TransitPlan transitPlan) {
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        sources.add("campus_connector:" + connector.id());
        if (connector.sourceRefs() != null) {
            sources.addAll(connector.sourceRefs());
        }
        if (transitPlan.source() != null && !transitPlan.source().isBlank()) {
            sources.add(transitPlan.source());
        }
        if (transitPlan.segments() != null) {
            for (TransitSegment segment : transitPlan.segments()) {
                if (segment.source() != null && !segment.source().isBlank()) {
                    sources.add(segment.source());
                }
                if (segment.lines() != null) {
                    for (TransitLine line : segment.lines()) {
                        if (line.source() != null && !line.source().isBlank()) {
                            sources.add(line.source());
                        }
                    }
                }
            }
        }
        return List.copyOf(sources);
    }

    private List<String> successWarnings(List<ProviderFailure> failures) {
        List<String> warnings = new ArrayList<>();
        for (ProviderFailure failure : failures) {
            warnings.add("校门 " + failure.gateId() + " 的出校点城市段查询失败（"
                    + failure.code() + "），本次未返回该出校点的候选");
        }
        return warnings;
    }

    private JourneyApiException mapAllFailures(List<ProviderFailure> failures, String requestId) {
        ProviderFailure primary = failures.get(0);
        boolean timeout = failures.stream().anyMatch(f -> f.code() == TransitErrorCode.NETWORK_TIMEOUT);
        boolean rateLimited = failures.stream().anyMatch(
                f -> f.code() == TransitErrorCode.AMAP_QUOTA_ERROR);
        String gates = failures.stream().map(ProviderFailure::gateId)
                .filter(gate -> gate != null && !gate.isBlank())
                .distinct()
                .collect(Collectors.joining("、"));
        String detail = primary.message() == null || primary.message().isBlank()
                ? "上游城市公共交通服务不可用"
                : primary.message();
        String message = "所有校门出校点的城市段查询均失败"
                + (gates.isBlank() ? "" : "（" + gates + "）")
                + "，本次没有可展示的候选方案。上游错误：" + detail;
        if (timeout) {
            return new JourneyApiException(HttpStatus.GATEWAY_TIMEOUT, "PROVIDER_TIMEOUT",
                    message, true, requestId);
        }
        if (rateLimited) {
            return new JourneyApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_RATE_LIMITED",
                    message, true, requestId);
        }
        if (primary.code() == TransitErrorCode.INVALID_PARAMETER) {
            return new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARAMETER",
                    message, false, requestId);
        }
        if (primary.code() == TransitErrorCode.COORDINATE_SYSTEM_UNSUPPORTED) {
            return new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "COORDINATE_SYSTEM_MISMATCH", message, false, requestId);
        }
        if (primary.code() == TransitErrorCode.RESPONSE_STRUCTURE_ERROR) {
            return new JourneyApiException(HttpStatus.BAD_GATEWAY, "RESPONSE_STRUCTURE_ERROR",
                    message, true, requestId);
        }
        return new JourneyApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_ERROR",
                message, true, requestId);
    }

    private CampusConnectorService.Resolution resolveConnectors(String originId,
                                                               OffsetDateTime departure,
                                                               String requestId) {
        try {
            return connectorService.resolve(originId, departure);
        } catch (PlaceNotFoundException ex) {
            throw new JourneyApiException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND",
                    ex.getMessage(), false, requestId);
        } catch (CampusConnectorDataLoadException | PlaceDataLoadException ex) {
            log.error("校园数据加载失败 requestId={}", requestId, ex);
            throw new JourneyApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR",
                    "校园地点或接驳数据加载失败，请稍后重试", true, requestId);
        } catch (RuntimeException ex) {
            log.error("接驳解析失败 requestId={} originId={}", requestId, originId, ex);
            throw new JourneyApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR",
                    "校园接驳数据异常，请稍后重试", true, requestId);
        }
    }

    private String campusDataVersion(String requestId) {
        try {
            return "places=" + placeService.dataVersion()
                    + ";connectors=" + connectorService.dataVersion();
        } catch (PlaceDataLoadException | CampusConnectorDataLoadException ex) {
            log.error("读取校园数据版本失败 requestId={}", requestId, ex);
            throw new JourneyApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR",
                    "校园数据版本读取失败，请稍后重试", true, requestId);
        }
    }

    private String unavailableSummary(CampusConnectorService.Resolution resolution) {
        if (resolution.unavailable() == null || resolution.unavailable().isEmpty()) {
            return "该起点没有已审核的校园接驳";
        }
        return resolution.unavailable().stream()
                .map(unavailable -> unavailable.gateId() + "（"
                        + (unavailable.reasonCode() == null ? "不可用" : unavailable.reasonCode()) + "）")
                .collect(Collectors.joining("、"));
    }

    private PlaceCandidate loadPlace(String placeId, String label, String requestId) {
        try {
            return placeService.getById(placeId);
        } catch (PlaceNotFoundException ex) {
            throw new JourneyApiException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND",
                    label + "不存在：" + placeId, false, requestId);
        } catch (PlaceDataLoadException ex) {
            log.error("地点数据加载失败 requestId={} placeId={}", requestId, placeId, ex);
            throw new JourneyApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR",
                    "校园地点数据加载失败，请稍后重试", true, requestId);
        }
    }

    private GeoPosition requireRoutingPosition(PlaceCandidate place, String label, String requestId) {
        if (!place.routingReady() || place.routingPosition() == null) {
            String reason = place.unavailableReason() == null || place.unavailableReason().isBlank()
                    ? "该地点缺少经核实的算路位置"
                    : place.unavailableReason();
            throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLACE_NOT_ROUTABLE",
                    label + "“" + place.name() + "”暂不可用于算路：" + reason, false, requestId);
        }
        GeoPosition position = place.routingPosition();
        requireCoordinates(position.longitude(), position.latitude(),
                label + "“" + place.name() + "”的算路位置", requestId);
        return position;
    }

    private void validateConnector(CampusConnector connector, String requestId) {
        if (connector.gateExitPoint() == null) {
            throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CAMPUS_CONNECTOR_UNAVAILABLE",
                    "接驳“" + connector.id() + "”缺少实际出校点，不能作为城市段起点",
                    false, requestId);
        }
        if (connector.durationSeconds() == null) {
            throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CAMPUS_CONNECTOR_UNAVAILABLE",
                    "接驳“" + connector.id() + "”缺少步行耗时，无法计算出发时刻",
                    false, requestId);
        }
        requireCoordinates(connector.gateExitPoint().longitude(), connector.gateExitPoint().latitude(),
                "接驳“" + connector.id() + "”的出校点", requestId);
        validateCoordinateSystem(connector.gateExitPoint().coordinateSystem(),
                "接驳“" + connector.id() + "”的出校点", requestId);
        if (connector.segments() != null) {
            for (CampusConnectorSegment segment : connector.segments()) {
                CampusConnectorGeometry geometry = segment.geometry();
                if (geometry == null || geometry.points() == null || geometry.points().isEmpty()) {
                    continue;
                }
                String system = geometry.coordinateSystem();
                if (system == null || !isWgs84(system)) {
                    throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "COORDINATE_SYSTEM_MISMATCH",
                            "接驳“" + connector.id() + "”的分段几何坐标系为 " + system
                                    + "，校园步行几何只支持 WGS84",
                            false, requestId);
                }
            }
        }
    }

    private TransitPoint toTransitPoint(GeoPosition position, String label, String requestId) {
        requireCoordinates(position.longitude(), position.latitude(), label, requestId);
        return new TransitPoint(position.pointId(), position.longitude(), position.latitude(),
                position.coordinateSystem());
    }

    private TransitPoint toTransitPoint(CampusConnectorPoint point, String label, String requestId) {
        requireCoordinates(point.longitude(), point.latitude(), label, requestId);
        return new TransitPoint(point.pointId(), point.longitude(), point.latitude(),
                point.coordinateSystem());
    }

    private void requireCoordinates(Double longitude, Double latitude, String label, String requestId) {
        if (longitude == null || latitude == null) {
            throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "COORDINATE_SYSTEM_MISMATCH",
                    label + "缺少经纬度，不能发起城市段查询", false, requestId);
        }
    }

    private void validateCoordinateSystem(String coordinateSystem, String label, String requestId) {
        if (coordinateSystem == null || coordinateSystem.isBlank()) {
            throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "COORDINATE_SYSTEM_MISMATCH", label + "缺少坐标系", false, requestId);
        }
        if (!SUPPORTED_COORDINATE_SYSTEMS.contains(coordinateSystem.trim().toUpperCase(Locale.ROOT))) {
            throw new JourneyApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "COORDINATE_SYSTEM_MISMATCH",
                    label + "坐标系为 " + coordinateSystem + "，仅支持 WGS84/GCJ-02", false, requestId);
        }
    }

    private static boolean isWgs84(String coordinateSystem) {
        String normalized = coordinateSystem.trim().toUpperCase(Locale.ROOT);
        return "WGS84".equals(normalized) || "WGS-84".equals(normalized);
    }

    private String requireText(String value, String label, String requestId) {
        if (value == null || value.isBlank()) {
            throw new JourneyApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "缺少必填参数：" + label, false, requestId);
        }
        return value.trim();
    }

    private OffsetDateTime parseDeparture(String raw, String requestId) {
        if (raw == null || raw.isBlank()) {
            throw new JourneyApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "缺少必填参数：出发时间 departureTime", false, requestId);
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(raw.trim());
            return parsed.atZoneSameInstant(BUSINESS_ZONE).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            throw new JourneyApiException(HttpStatus.BAD_REQUEST, "DEPARTURE_TIME_INVALID",
                    "出发时间必须是带时区偏移的 ISO-8601 时间，例如 2026-11-16T15:16:00+08:00",
                    false, requestId);
        }
    }

    private TransitPreference parsePreference(String raw, String requestId) {
        if (raw == null || raw.isBlank()) {
            throw new JourneyApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "缺少必填参数：偏好 preference", false, requestId);
        }
        try {
            return TransitPreference.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new JourneyApiException(HttpStatus.BAD_REQUEST, "INVALID_PREFERENCE",
                    "偏好选项不合法，必须是 RECOMMENDED、LESS_WALKING 或 FEWER_TRANSFERS",
                    false, requestId);
        }
    }

    private BigDecimal sumWalking(Double connectorWalkMeters, Integer providerWalkingMeters) {
        if (connectorWalkMeters == null || providerWalkingMeters == null) {
            return null;
        }
        return BigDecimal.valueOf(connectorWalkMeters)
                .add(BigDecimal.valueOf(providerWalkingMeters));
    }

    private JourneyPoint gateExitPoint(CampusConnector connector) {
        CampusConnectorPoint point = connector.gateExitPoint();
        return new JourneyPoint(point.pointId(), connector.gateName(), point.longitude(),
                point.latitude(), point.coordinateSystem(), point.positionRole());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).atZoneSameInstant(BUSINESS_ZONE).toOffsetDateTime();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "上游服务未提供具体原因" : message;
    }

    /** 单个出校点城市段查询失败记录；只用于 warnings 与全部失败时的错误映射。 */
    private record ProviderFailure(String gateId, TransitErrorCode code, String message) {
    }
}
