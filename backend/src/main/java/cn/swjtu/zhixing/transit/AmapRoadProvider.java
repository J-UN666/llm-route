package cn.swjtu.zhixing.transit;

import static cn.swjtu.zhixing.transit.AmapTransitProvider.appendPolyline;
import static cn.swjtu.zhixing.transit.AmapTransitProvider.classifyProviderCode;
import static cn.swjtu.zhixing.transit.AmapTransitProvider.decimal;
import static cn.swjtu.zhixing.transit.AmapTransitProvider.formatCoordinate;
import static cn.swjtu.zhixing.transit.AmapTransitProvider.geometry;
import static cn.swjtu.zhixing.transit.AmapTransitProvider.hasTimeoutCause;
import static cn.swjtu.zhixing.transit.AmapTransitProvider.integer;
import static cn.swjtu.zhixing.transit.AmapTransitProvider.providerErrorMessage;
import static cn.swjtu.zhixing.transit.AmapTransitProvider.text;
import static cn.swjtu.zhixing.transit.AmapTransitProvider.validBusinessEnvelope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 将高德驾车和步行路线转成行程服务可复用的城市段。 */
@Component
public class AmapRoadProvider {
    private static final String AMAP_HOST = "https://restapi.amap.com";
    private static final int TAXI_WAIT_SECONDS = 300;

    public enum Mode {
        TAXI("driving", "AMAP_V3_DRIVING"), WALK("walking", "AMAP_V3_WALKING");

        private final String service;
        private final String source;

        Mode(String service, String source) {
            this.service = service;
            this.source = source;
        }
    }

    private final RestClient client;
    private final AmapProperties properties;
    private final ObjectMapper mapper;

    public AmapRoadProvider(RestClient.Builder builder, AmapProperties properties, ObjectMapper mapper) {
        this.client = AmapTransitProvider.buildRestClient(builder, properties, properties.getRoadBaseUrl());
        this.properties = properties;
        this.mapper = mapper;
    }

    public TransitSearchResult search(Mode mode, TransitPoint origin, TransitPoint destination,
                                      OffsetDateTime exitTime, String personalKey) {
        OffsetDateTime queriedAt = OffsetDateTime.now(AmapTransitProvider.BUSINESS_ZONE);
        String key = personalKey == null ? properties.getWebServiceKey() : personalKey;
        if (key.isBlank()) {
            return failure(queriedAt, exitTime, TransitErrorCode.PROVIDER_NOT_CONFIGURED, "高德 Web 服务未配置");
        }
        try {
            String from = formatCoordinate(CoordinateConverter.toGcj02(origin));
            String to = formatCoordinate(CoordinateConverter.toGcj02(destination));
            // 个人 Key 只发往高德官方地址。基础地址可配置，便于复用项目现有的本地测试方式。
            String endpoint = (personalKey == null ? "" : AMAP_HOST) + "/v3/direction/" + mode.service;
            String body = client.get().uri(endpoint, uri -> {
                uri.queryParam("key", key).queryParam("origin", from).queryParam("destination", to)
                        .queryParam("output", "JSON");
                if (mode == Mode.TAXI) {
                    uri.queryParam("strategy", 10).queryParam("extensions", "all");
                }
                return uri.build();
            }).retrieve().body(String.class);
            return parse(mode, body, queriedAt, exitTime);
        } catch (CoordinateSystemUnsupportedException ex) {
            return failure(queriedAt, exitTime, TransitErrorCode.COORDINATE_SYSTEM_UNSUPPORTED, "输入坐标系不受支持");
        } catch (ResourceAccessException ex) {
            TransitErrorCode code = hasTimeoutCause(ex) ? TransitErrorCode.NETWORK_TIMEOUT : TransitErrorCode.NETWORK_ERROR;
            return failure(queriedAt, exitTime, code, "高德驾车或步行服务连接失败");
        } catch (RestClientResponseException ex) {
            TransitErrorCode code = ex.getStatusCode().value() == 429 ? TransitErrorCode.AMAP_QUOTA_ERROR
                    : TransitErrorCode.PROVIDER_ERROR;
            return failure(queriedAt, exitTime, code, "高德驾车或步行服务请求失败");
        } catch (RestClientException ex) {
            return failure(queriedAt, exitTime, TransitErrorCode.NETWORK_ERROR, "高德网络请求失败");
        } catch (JsonProcessingException ex) {
            return failure(queriedAt, exitTime, TransitErrorCode.RESPONSE_STRUCTURE_ERROR, "高德响应结构错误");
        }
    }

    private TransitSearchResult parse(Mode mode, String body, OffsetDateTime queriedAt, OffsetDateTime exitTime)
            throws JsonProcessingException {
        JsonNode root = body == null ? null : mapper.readTree(body);
        if (!validBusinessEnvelope(root)) {
            return failure(queriedAt, exitTime, TransitErrorCode.RESPONSE_STRUCTURE_ERROR, "高德响应结构错误");
        }
        if ("0".equals(text(root, "status"))) {
            String infoCode = text(root, "infocode");
            TransitErrorCode code = classifyProviderCode(infoCode);
            return failure(queriedAt, exitTime, code, providerErrorMessage(code, infoCode));
        }
        JsonNode route = root.path("route");
        JsonNode paths = route.path("paths");
        if (!paths.isArray() && !"0".equals(text(root, "count"))) {
            return failure(queriedAt, exitTime, TransitErrorCode.RESPONSE_STRUCTURE_ERROR, "高德缺少路线列表");
        }
        var plans = new ArrayList<TransitPlan>();
        for (JsonNode path : paths) {
            Integer duration = integer(path, "duration");
            if (duration == null || duration < 0 || duration > Integer.MAX_VALUE - TAXI_WAIT_SECONDS) {
                continue;
            }
            boolean taxi = mode == Mode.TAXI;
            Integer distance = integer(path, "distance");
            var points = new ArrayList<List<Double>>();
            for (JsonNode step : path.path("steps")) {
                appendPolyline(points, text(step, "polyline"));
            }
            var leg = new TransitSegment(mode.name(), duration, taxi ? null : distance,
                    null, null, List.of(), geometry(points), mode.source);
            // 等车在到达校门后开始，时长计入全程，并单独展示在路线分段中。
            var segments = taxi ? List.of(new TransitSegment("TAXI_WAIT", TAXI_WAIT_SECONDS, 0,
                    null, null, List.of(), null, "FIXED_TAXI_WAIT"), leg) : List.of(leg);
            plans.add(new TransitPlan(duration + (taxi ? TAXI_WAIT_SECONDS : 0), taxi ? Integer.valueOf(0) : distance,
                    0, taxi ? decimal(route, "taxi_cost") : null, segments, mode.source, distance));
        }
        if (!paths.isEmpty() && plans.isEmpty()) {
            return failure(queriedAt, exitTime, TransitErrorCode.RESPONSE_STRUCTURE_ERROR, "高德路线缺少有效耗时");
        }
        return new TransitSearchResult(AmapTransitProvider.PROVIDER, queriedAt, exitTime, exitTime, 0,
                plans.isEmpty() ? TransitStatus.NO_ROUTE : TransitStatus.OK, plans);
    }

    private TransitSearchResult failure(OffsetDateTime queriedAt, OffsetDateTime exitTime, TransitErrorCode code,
                                        String message) {
        return new TransitSearchResult(AmapTransitProvider.PROVIDER, queriedAt, exitTime, exitTime, 0,
                TransitStatus.NO_ROUTE, List.of(), code, message);
    }
}
