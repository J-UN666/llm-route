package cn.swjtu.zhixing.transit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 高德 v3 公交/地铁综合查询适配器。 */
@Component
@Profile("!demo")
public class AmapTransitProvider implements TransitProvider {
    public static final String PROVIDER = "AMAP";
    public static final String SOURCE = "AMAP_V3_TRANSIT_INTEGRATED";
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> AUTH_CODES = Set.of(
            "10001", "10002", "10005", "10007", "10008", "10009", "10012", "10041");
    private static final Set<String> QUOTA_CODES = Set.of(
            "10003", "10004", "10010", "10014", "10015", "10019", "10020", "10021", "10044");
    private static final Set<String> PARAMETER_CODES = Set.of("20000", "20001", "20002");
    private static final Set<String> RAILWAY_PLACEHOLDER_FIELDS = Set.of("via_stops", "alters", "spaces");

    private final RestClient restClient;
    private final AmapProperties properties;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final Clock clock;

    /** Spring 使用的构造器；JDK HttpClient 的连接/读取超时在此集中设置。 */
    @Autowired
    public AmapTransitProvider(RestClient.Builder restClientBuilder,
                               AmapProperties properties,
                               ObjectMapper objectMapper,
                               Validator validator) {
        this(buildRestClient(restClientBuilder, properties), properties, objectMapper,
                Clock.systemUTC(), validator);
    }

    /** 可控 HTTP 服务测试用构造器。 */
    public AmapTransitProvider(RestClient restClient,
                               AmapProperties properties,
                               ObjectMapper objectMapper,
                               Clock clock,
                               Validator validator) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.validator = validator == null ? defaultValidator() : validator;
    }

    /** 便于脱敏响应重放测试的简化构造器。 */
    public AmapTransitProvider(RestClient restClient, AmapProperties properties, ObjectMapper objectMapper) {
        this(restClient, properties, objectMapper, Clock.systemUTC(), defaultValidator());
    }

    /** 使用固定时钟的测试构造器，仍采用默认 Bean Validation 实现。 */
    public AmapTransitProvider(RestClient restClient,
                               AmapProperties properties,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this(restClient, properties, objectMapper, clock, defaultValidator());
    }

    @Override
    public TransitSearchResult search(TransitSearchRequest request) {
        OffsetDateTime queriedAt = now();
        if (request == null) {
            return error(queriedAt, null, null, 0, TransitErrorCode.INVALID_PARAMETER, "请求不能为空");
        }
        OffsetDateTime requested = request.departureTime();
        Set<ConstraintViolation<TransitSearchRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            return error(queriedAt, requested, null, 0, TransitErrorCode.INVALID_PARAMETER,
                    "请求参数不合法");
        }
        ZonedDateTime local = requested.atZoneSameInstant(BUSINESS_ZONE);
        ZonedDateTime rounded = roundToProviderMinute(local);
        int roundingWaitSeconds = roundingWaitSeconds(local, rounded);
        OffsetDateTime providerDeparture = rounded.toOffsetDateTime();
        try {
            TransitPoint origin = CoordinateConverter.toGcj02(request.originPoint());
            TransitPoint destination = CoordinateConverter.toGcj02(request.destinationPoint());
            if (properties.getWebServiceKey() == null || properties.getWebServiceKey().isBlank()) {
                return error(queriedAt, requested, providerDeparture, roundingWaitSeconds,
                        TransitErrorCode.PROVIDER_NOT_CONFIGURED, "高德 Web 服务未配置");
            }
            String body = callAmap(origin, destination, rounded, request.preference());
            return mapResponse(body, queriedAt, requested, providerDeparture, roundingWaitSeconds);
        } catch (CoordinateSystemUnsupportedException ex) {
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds,
                    TransitErrorCode.COORDINATE_SYSTEM_UNSUPPORTED, "输入坐标系不受支持");
        } catch (IllegalArgumentException ex) {
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds, TransitErrorCode.INVALID_PARAMETER,
                    "请求参数不合法");
        } catch (ResourceAccessException ex) {
            TransitErrorCode code = hasTimeoutCause(ex) ? TransitErrorCode.NETWORK_TIMEOUT
                    : TransitErrorCode.NETWORK_ERROR;
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds, code,
                    code == TransitErrorCode.NETWORK_TIMEOUT ? "高德查询超时" : "高德网络请求失败");
        } catch (RestClientResponseException ex) {
            return mapHttpError(ex, queriedAt, requested, providerDeparture, roundingWaitSeconds);
        } catch (RestClientException ex) {
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds, TransitErrorCode.NETWORK_ERROR,
                    "高德网络请求失败");
        }
    }

    private String callAmap(TransitPoint origin, TransitPoint destination,
                            ZonedDateTime departure, TransitPreference preference) {
        String originValue = formatCoordinate(origin);
        String destinationValue = formatCoordinate(destination);
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .queryParam("key", properties.getWebServiceKey())
                        .queryParam("origin", originValue)
                        .queryParam("destination", destinationValue)
                        .queryParam("city", "成都")
                        .queryParam("date", DATE_FORMAT.format(departure))
                        .queryParam("time", TIME_FORMAT.format(departure))
                        .queryParam("strategy", strategy(preference))
                        .queryParam("output", "JSON")
                        .queryParam("extensions", "all")
                        .build())
                .retrieve()
                .body(String.class);
    }

    private TransitSearchResult mapResponse(String body,
                                             OffsetDateTime queriedAt,
                                             OffsetDateTime requested,
                                             OffsetDateTime providerDeparture,
                                             int roundingWaitSeconds) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception ex) {
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds,
                    TransitErrorCode.RESPONSE_STRUCTURE_ERROR, "高德响应结构错误");
        }
        if (!validBusinessEnvelope(root)) {
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds,
                    TransitErrorCode.RESPONSE_STRUCTURE_ERROR, "高德响应结构错误");
        }
        String status = text(root, "status");
        String infoCode = text(root, "infocode");
        if ("0".equals(status)) {
            TransitErrorCode code = classifyProviderCode(infoCode);
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds, code,
                    providerErrorMessage(code, infoCode));
        }
        JsonNode route = root.get("route");
        if (route == null || !route.isObject() || !route.has("transits") || !route.get("transits").isArray()) {
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds,
                    TransitErrorCode.RESPONSE_STRUCTURE_ERROR, "高德响应结构错误");
        }
        JsonNode transitNodes = route.get("transits");
        if (transitNodes.isEmpty()) {
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds,
                    TransitErrorCode.NO_ROUTE, "未找到公交或地铁方案");
        }
        List<TransitPlan> plans = new ArrayList<>();
        try {
            for (JsonNode node : transitNodes) {
                plans.add(parsePlan(node));
            }
        } catch (ResponseStructureException ex) {
            return error(queriedAt, requested, providerDeparture, roundingWaitSeconds,
                    TransitErrorCode.RESPONSE_STRUCTURE_ERROR, "高德响应结构错误");
        }
        return new TransitSearchResult(PROVIDER, queriedAt, requested, providerDeparture,
                roundingWaitSeconds, TransitStatus.OK, plans, null, null);
    }

    private TransitPlan parsePlan(JsonNode node) {
        if (node == null || !node.isObject() || !node.has("segments") || !node.get("segments").isArray()) {
            throw new ResponseStructureException();
        }
        List<TransitSegment> segments = new ArrayList<>();
        for (JsonNode segment : node.get("segments")) {
            if (segment == null || !segment.isObject()) {
                throw new ResponseStructureException();
            }
            parseWalking(segment.get("walking"), segments);
            parseBus(segment.get("bus"), segments);
            parseRailway(segment.get("railway"), segments);
            parseTaxi(segment.get("taxi"), segments);
            if (segment.get("walking") == null && segment.get("bus") == null
                    && segment.get("railway") == null && segment.get("taxi") == null) {
                throw new ResponseStructureException();
            }
        }
        if (segments.isEmpty()) {
            throw new ResponseStructureException();
        }
        Integer walkingMeters = integer(node, "walking_distance");
        if (walkingMeters == null) {
            walkingMeters = completeWalkingDistance(segments);
        }
        long rideSegments = segments.stream().filter(s -> !s.lines().isEmpty()).count();
        return new TransitPlan(integer(node, "duration"), walkingMeters, (int) Math.max(0, rideSegments - 1),
                decimal(node, "cost", "transit_fee"), segments, SOURCE);
    }

    private void parseWalking(JsonNode walking, List<TransitSegment> output) {
        if (emptyTransport(walking)) {
            return;
        }
        if (!walking.isObject()
                || !hasAnyField(walking, "origin", "destination", "distance", "duration", "steps")) {
            throw new ResponseStructureException();
        }
        List<List<Double>> points = new ArrayList<>();
        JsonNode steps = walking.get("steps");
        if (steps != null && !steps.isNull()) {
            if (!steps.isArray()) {
                throw new ResponseStructureException();
            }
            for (JsonNode step : steps) {
                if (step == null || !step.isObject() || step.isEmpty()) {
                    throw new ResponseStructureException();
                }
                appendPolyline(points, text(step, "polyline"));
            }
        }
        output.add(new TransitSegment("WALK", integer(walking, "duration"), integer(walking, "distance"),
                null, null, List.of(), geometry(points), SOURCE));
    }

    private void parseBus(JsonNode bus, List<TransitSegment> output) {
        if (emptyTransport(bus)) {
            return;
        }
        if (!bus.isObject() || !bus.has("buslines") || !bus.get("buslines").isArray()) {
            throw new ResponseStructureException();
        }
        // 同一 buslines 中是可替代的线路，按高德顺序选择第一条，不能拼成连续乘坐。
        TransitLine selected = null;
        for (JsonNode line : bus.get("buslines")) {
            if (line == null || !line.isObject() || line.isEmpty()
                    || (text(line, "name") == null && text(line, "id") == null)) {
                throw new ResponseStructureException();
            }
            JsonNode departure = line.get("departure_stop");
            JsonNode arrival = line.get("arrival_stop");
            List<List<Double>> linePoints = new ArrayList<>();
            appendPolyline(linePoints, text(line, "polyline"));
            TransitLine candidate = new TransitLine(text(line, "name"), text(line, "id"), text(line, "type"),
                    text(line, "direction"), text(departure, "name"), text(arrival, "name"),
                    integer(line, "duration"), integer(line, "via_num"), geometry(linePoints), SOURCE);
            if (selected == null) {
                selected = candidate;
            }
        }
        if (selected == null) {
            // 步行末段常带有 bus: {buslines: []} 占位。
            return;
        }
        output.add(new TransitSegment(modeForBus(List.of(selected)), selected.durationSeconds(), null,
                selected.boardingStation(), selected.alightingStation(), List.of(selected), selected.geometry(), SOURCE));
    }

    private void parseRailway(JsonNode railway, List<TransitSegment> output) {
        if (emptyTransport(railway) || emptyRailwayPlaceholder(railway)) {
            return;
        }
        if (!railway.isObject() || (text(railway, "name") == null && text(railway, "id") == null)) {
            throw new ResponseStructureException();
        }
        List<List<Double>> points = new ArrayList<>();
        appendPolyline(points, text(railway, "polyline"));
        TransitLine line = new TransitLine(text(railway, "name"), text(railway, "id"), "RAILWAY",
                text(railway, "direction"), text(railway.get("departure_stop"), "name"),
                text(railway.get("arrival_stop"), "name"), integer(railway, "duration"),
                integer(railway, "via_num"), geometry(points), SOURCE);
        output.add(new TransitSegment("RAILWAY", integer(railway, "duration"), null,
                line.boardingStation(), line.alightingStation(), List.of(line), geometry(points), SOURCE));
    }

    private void parseTaxi(JsonNode taxi, List<TransitSegment> output) {
        if (emptyTransport(taxi)) {
            return;
        }
        if (!taxi.isObject()
                || !hasAnyField(taxi, "origin", "destination", "distance", "duration", "polyline")) {
            throw new ResponseStructureException();
        }
        List<List<Double>> points = new ArrayList<>();
        appendPolyline(points, text(taxi, "polyline"));
        output.add(new TransitSegment("TAXI", integer(taxi, "duration"), null,
                null, null, List.of(), geometry(points), SOURCE));
    }

    private TransitSearchResult mapHttpError(RestClientResponseException ex,
                                             OffsetDateTime queriedAt,
                                             OffsetDateTime requested,
                                             OffsetDateTime providerDeparture,
                                             int roundingWaitSeconds) {
        String body = ex.getResponseBodyAsString();
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root != null && root.isObject()) {
                if (!validBusinessEnvelope(root)) {
                    return error(queriedAt, requested, providerDeparture, roundingWaitSeconds,
                            TransitErrorCode.RESPONSE_STRUCTURE_ERROR, "高德响应结构错误");
                }
                String infoCode = text(root, "infocode");
                if ("0".equals(text(root, "status"))) {
                    TransitErrorCode code = classifyProviderCode(infoCode);
                    return error(queriedAt, requested, providerDeparture, roundingWaitSeconds, code,
                            providerErrorMessage(code, infoCode));
                }
            }
        } catch (Exception ignored) {
            // 不把第三方原始响应写入错误信息。
        }
        TransitErrorCode code = ex.getStatusCode().value() == 429
                ? TransitErrorCode.AMAP_QUOTA_ERROR
                : (ex.getStatusCode().is4xxClientError()
                ? TransitErrorCode.AMAP_AUTHORIZATION_ERROR : TransitErrorCode.PROVIDER_ERROR);
        return error(queriedAt, requested, providerDeparture, roundingWaitSeconds, code, "高德服务请求失败");
    }

    private static RestClient buildRestClient(RestClient.Builder builder, AmapProperties properties) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        return builder.requestFactory(requestFactory).baseUrl(properties.getTransitBaseUrl()).build();
    }

    private static Validator defaultValidator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).atZoneSameInstant(BUSINESS_ZONE).toOffsetDateTime();
    }

    private static ZonedDateTime roundToProviderMinute(ZonedDateTime local) {
        if (local.getSecond() == 0 && local.getNano() == 0) {
            return local;
        }
        return local.plusMinutes(1).withSecond(0).withNano(0);
    }

    private static int roundingWaitSeconds(ZonedDateTime original, ZonedDateTime rounded) {
        long nanos = Duration.between(original, rounded).toNanos();
        return (int) Math.ceil(nanos / 1_000_000_000.0);
    }

    private static String formatCoordinate(TransitPoint point) {
        return String.format(Locale.ROOT, "%.6f,%.6f", point.longitude(), point.latitude());
    }

    private static int strategy(TransitPreference preference) {
        return switch (preference) {
            case RECOMMENDED -> 0;
            case FEWER_TRANSFERS -> 2;
            case LESS_WALKING -> 3;
        };
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull() || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode valueNode = node.get(field);
        if (!valueNode.isTextual() && !valueNode.isNumber()) {
            return null;
        }
        String value = valueNode.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Integer integer(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            try {
                return (int) Math.round(Double.parseDouble(value.trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static BigDecimal decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                try {
                    return new BigDecimal(value.trim());
                } catch (NumberFormatException ignored) {
                    // 尝试下一个候选字段；未知费用仍保持 null。
                }
            }
        }
        return null;
    }

    private static void appendPolyline(List<List<Double>> target, String polyline) {
        if (polyline == null || polyline.isBlank()) {
            return;
        }
        for (String pair : polyline.split(";")) {
            String[] values = pair.trim().split(",");
            if (values.length < 2) {
                continue;
            }
            try {
                target.add(List.of(Double.parseDouble(values[0]), Double.parseDouble(values[1])));
            } catch (NumberFormatException ignored) {
                // 单个坏点不影响其他可用几何点。
            }
        }
    }

    private static TransitGeometry geometry(List<List<Double>> points) {
        return points.isEmpty() ? null : new TransitGeometry("GCJ-02", points);
    }

    private static String modeForBus(List<TransitLine> lines) {
        for (TransitLine line : lines) {
            String type = line.type();
            if (type != null && (type.contains("地铁") || type.toUpperCase(Locale.ROOT).contains("SUBWAY"))) {
                return "SUBWAY";
            }
        }
        for (TransitLine line : lines) {
            String name = line.name();
            if (name != null && name.contains("地铁")) {
                return "SUBWAY";
            }
        }
        return "BUS";
    }

    private static Integer completeWalkingDistance(List<TransitSegment> segments) {
        List<TransitSegment> walks = segments.stream().filter(s -> "WALK".equals(s.mode())).toList();
        if (walks.isEmpty() || walks.stream().anyMatch(s -> s.walkingMeters() == null)) {
            return null;
        }
        return walks.stream().map(TransitSegment::walkingMeters).reduce(0, Integer::sum);
    }

    /** 高德以 null、空对象、空数组或空字符串表示没有该交通方式。 */
    private static boolean emptyTransport(JsonNode node) {
        return node == null || node.isNull()
                || (node.isContainerNode() && node.isEmpty())
                || (node.isTextual() && node.asText().isBlank());
    }

    /** 真实 v3 响应即使没有铁路，也会返回这些空集合。只识别已知占位字段。 */
    private static boolean emptyRailwayPlaceholder(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!RAILWAY_PLACEHOLDER_FIELDS.contains(field.getKey()) || !emptyTransport(field.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAnyField(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validBusinessEnvelope(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        String status = text(root, "status");
        String infoCode = text(root, "infocode");
        if (!("0".equals(status) || "1".equals(status))
                || infoCode == null || !infoCode.matches("[0-9]{5}")) {
            return false;
        }
        // 成功状态必须伴随成功码；失败状态也不能携带成功码。
        return "1".equals(status) == "10000".equals(infoCode);
    }

    private static TransitErrorCode classifyProviderCode(String infoCode) {
        if (infoCode == null || !infoCode.matches("[0-9]{5}")) {
            return TransitErrorCode.RESPONSE_STRUCTURE_ERROR;
        }
        if (AUTH_CODES.contains(infoCode)) {
            return TransitErrorCode.AMAP_AUTHORIZATION_ERROR;
        }
        if (QUOTA_CODES.contains(infoCode)) {
            return TransitErrorCode.AMAP_QUOTA_ERROR;
        }
        if (PARAMETER_CODES.contains(infoCode)) {
            return TransitErrorCode.INVALID_PARAMETER;
        }
        return TransitErrorCode.PROVIDER_ERROR;
    }

    private static String providerErrorMessage(TransitErrorCode code, String infoCode) {
        String suffix = infoCode == null || infoCode.isBlank() ? "" : "（infocode=" + infoCode + "）";
        return switch (code) {
            case AMAP_AUTHORIZATION_ERROR -> "高德服务权限错误" + suffix;
            case AMAP_QUOTA_ERROR -> "高德服务额度或频率限制" + suffix;
            case INVALID_PARAMETER -> "高德服务拒绝了查询参数" + suffix;
            default -> "高德服务返回错误" + suffix;
        };
    }

    private TransitSearchResult error(OffsetDateTime queriedAt,
                                      OffsetDateTime requested,
                                      OffsetDateTime providerDeparture,
                                      int roundingWaitSeconds,
                                      TransitErrorCode code,
                                      String message) {
        return new TransitSearchResult(PROVIDER, queriedAt, requested, providerDeparture,
                roundingWaitSeconds, TransitStatus.NO_ROUTE, List.of(), code, message);
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException
                    || current.getClass().getName().contains("Timeout")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("timeout") || normalized.contains("timed out")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class ResponseStructureException extends RuntimeException {
    }
}
