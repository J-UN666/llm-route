package cn.swjtu.zhixing.assistant;

import cn.swjtu.zhixing.serviceconfig.PersonalServices.Credentials;
import cn.swjtu.zhixing.campus.CampusConnectorService;
import cn.swjtu.zhixing.campus.PlaceCandidate;
import cn.swjtu.zhixing.campus.PlaceKind;
import cn.swjtu.zhixing.campus.PlaceService;
import cn.swjtu.zhixing.journey.JourneyRequest;
import cn.swjtu.zhixing.journey.JourneyResponse;
import cn.swjtu.zhixing.journey.JourneyService;
import cn.swjtu.zhixing.transit.TransitPreference;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class AssistantTools {
    static final String SEARCH = "search_places";
    static final String CONNECTORS = "get_campus_connectors";
    static final String PLAN = "plan_journey";
    static final int MAX_CANDIDATES = 12;

    private static final Pattern PLACE_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,95}");
    private static final Map<String, Object> ID_SCHEMA = Map.of("type", "string", "maxLength", 96);
    private static final Map<String, Object> TIME_SCHEMA = Map.of("type", "string", "maxLength", 40,
            "description", "ISO-8601，须包含时区偏移。成都用 +08:00。");
    private static final Map<String, Object> BOOLEAN_SCHEMA = Map.of("type", "boolean");

    private static final Map<String, ToolDefinition> REGISTRY = Map.of(
            SEARCH, definition(SEARCH, "查找新地点。CAMPUS 是起点，CITY 是终点，GATE 是校门资料。关键词只放地点。",
                    Map.of("keyword", Map.of("type", "string", "minLength", 1, "maxLength", 80),
                            "scope", enumSchema(PlaceKind.values())), "keyword", "scope"),
            CONNECTORS, definition(CONNECTORS, "按校园起点和出发时刻检查校门接驳；查询完整路线无需先调用此工具。",
                    Map.of("originId", ID_SCHEMA, "departureTime", TIME_SCHEMA), "originId", "departureTime"),
            PLAN, definition(PLAN, """
                    申请按已解析的条件查路线。未提供的字段由 Java 沿用会话或填默认值。
                    只把本轮明确修改的时间与偏好传入。要求使用当前位置传 originIsCurrentLocation=true；
                    现在出发传 departNow=true；时间或偏好表述不明确时传对应 unclear 标志。
                    """, Map.of("originId", ID_SCHEMA, "destinationId", ID_SCHEMA, "departureTime", TIME_SCHEMA,
                    "preference", enumSchema(TransitPreference.values()), "originIsCurrentLocation", BOOLEAN_SCHEMA,
                    "departNow", BOOLEAN_SCHEMA, "departureUnclear", BOOLEAN_SCHEMA,
                    "preferenceUnclear", BOOLEAN_SCHEMA)));

    static final List<ToolDefinition> DEFINITIONS =
            List.of(REGISTRY.get(SEARCH), REGISTRY.get(CONNECTORS), REGISTRY.get(PLAN));

    sealed interface Arguments permits Search, Connectors, Plan {}
    record Search(String keyword, PlaceKind scope) implements Arguments {}
    record Connectors(String originId, OffsetDateTime departureTime) implements Arguments {}
    record Plan(String originId, String destinationId, OffsetDateTime departureTime, TransitPreference preference,
                boolean currentLocation, boolean departNow, boolean departureUnclear,
                boolean preferenceUnclear) implements Arguments {}

    private final ObjectMapper mapper;
    private final PlaceService places;
    private final CampusConnectorService connectors;
    private final JourneyService journeys;

    AssistantTools(ObjectMapper mapper, PlaceService places, CampusConnectorService connectors, JourneyService journeys) {
        this.mapper = mapper;
        this.places = places;
        this.connectors = connectors;
        this.journeys = journeys;
    }

    Arguments parse(ModelToolCall call, String requestId) {
        ToolDefinition tool = REGISTRY.get(call.name());
        if (tool == null) {
            throw error(AssistantErrorCodes.INVALID_TOOL_NAME, "模型调用了未声明的工具", requestId);
        }
        try {
            JsonNode value = parseObject(call.argumentsJson());
            Map<?, ?> fields = (Map<?, ?>) tool.parameters().get("properties");
            if (value.properties().stream().anyMatch(field -> !fields.containsKey(field.getKey()))) {
                throw new IllegalArgumentException();
            }
            return switch (call.name()) {
                case SEARCH -> new Search(text(value, "keyword", 80, true),
                        PlaceKind.valueOf(text(value, "scope", 10, true)));
                case CONNECTORS -> new Connectors(id(value, "originId", true), time(value, true));
                case PLAN -> parsePlan(value);
                default -> throw new IllegalStateException("工具注册表与解析器不一致");
            };
        } catch (IOException | IllegalArgumentException | DateTimeParseException ex) {
            throw error(AssistantErrorCodes.INVALID_TOOL_ARGUMENTS, "模型工具参数不符合约定", requestId);
        }
    }

    JsonNode parseObject(String raw) throws IOException {
        if (raw == null || raw.length() > 4096) {
            throw new IllegalArgumentException();
        }
        try (JsonParser parser = mapper.createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value = mapper.readTree(parser);
            if (value == null || !value.isObject() || parser.nextToken() != null) {
                throw new IllegalArgumentException();
            }
            return value;
        }
    }

    List<PlaceCandidate> search(Search args, Credentials credentials) {
        return places.searchForRouting(args.keyword(), args.scope(), credentials);
    }

    CampusConnectorService.Resolution connectors(Connectors args) {
        return connectors.resolve(args.originId(), args.departureTime());
    }

    JourneyResponse plan(JourneyRequest request) {
        return journeys.plan(request);
    }

    JourneyResponse plan(JourneyRequest request, Credentials credentials) {
        return journeys.plan(request, credentials);
    }

    PlaceCandidate place(String id, Credentials credentials) {
        return places.getById(id, credentials);
    }

    String campusDataVersion() {
        return "places=" + places.dataVersion() + ";connectors=" + connectors.dataVersion();
    }

    static boolean validId(String value) {
        return value != null && PLACE_ID.matcher(value).matches();
    }

    private static Plan parsePlan(JsonNode value) {
        String preference = text(value, "preference", 24, false);
        Plan args = new Plan(id(value, "originId", false), id(value, "destinationId", false),
                time(value, false), preference == null ? null : TransitPreference.valueOf(preference),
                flag(value, "originIsCurrentLocation"), flag(value, "departNow"),
                flag(value, "departureUnclear"), flag(value, "preferenceUnclear"));
        int timeOptions = (args.departureTime() == null ? 0 : 1)
                + (args.departNow() ? 1 : 0) + (args.departureUnclear() ? 1 : 0);
        if (timeOptions > 1 || (args.preferenceUnclear() && args.preference() != null)
                || (args.currentLocation() && args.originId() != null)) {
            throw new IllegalArgumentException();
        }
        return args;
    }

    private static String text(JsonNode value, String field, int max, boolean required) {
        JsonNode node = value.get(field);
        if (node == null && !required) {
            return null;
        }
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > max) {
            throw new IllegalArgumentException();
        }
        return node.textValue().trim();
    }

    private static String id(JsonNode value, String field, boolean required) {
        String id = text(value, field, 96, required);
        if (id != null && !validId(id)) {
            throw new IllegalArgumentException();
        }
        return id;
    }

    private static OffsetDateTime time(JsonNode value, boolean required) {
        String raw = text(value, "departureTime", 40, required);
        return raw == null ? null : OffsetDateTime.parse(raw);
    }

    private static boolean flag(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node != null && !node.isBoolean()) {
            throw new IllegalArgumentException();
        }
        return node != null && node.booleanValue();
    }

    private static ToolDefinition definition(String name, String description, Map<String, Object> properties,
                                             String... required) {
        return new ToolDefinition(name, description,
                Map.of("type", "object", "properties", properties,
                        "required", List.of(required), "additionalProperties", false));
    }

    private static Map<String, Object> enumSchema(Enum<?>[] options) {
        return Map.of("type", "string", "enum", Arrays.stream(options).map(Enum::name).toList());
    }

    private static AssistantApiException error(String code, String message, String requestId) {
        return new AssistantApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, false, requestId);
    }
}
