package cn.swjtu.zhixing.campus;

import cn.swjtu.zhixing.serviceconfig.PersonalServices.Credentials;
import cn.swjtu.zhixing.transit.AmapProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile("!demo")
class AmapCityPlaces {
    private static final String CITY = "成都市";
    private static final String ID_PREFIX = "amap:";
    private static final Pattern POI_ID = Pattern.compile("[A-Z0-9]{1,40}");
    private static final Set<String> QUOTA_CODES = Set.of(
            "10003", "10004", "10010", "10014", "10015", "10019", "10020", "10021", "10044", "40000");
    private static final Set<String> AUTH_CODES = Set.of(
            "10001", "10002", "10005", "10006", "10007", "10008", "10012", "10041");
    private final RestClient client;
    private final AmapProperties properties;

    AmapCityPlaces(RestClient.Builder builder, AmapProperties properties,
                   @Value("${amap.place-base-url:https://restapi.amap.com}") String baseUrl) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        this.client = builder.requestFactory(factory).baseUrl(baseUrl).build();
        this.properties = properties;
    }

    static boolean isOnlineId(String id) {
        return id != null && id.startsWith(ID_PREFIX);
    }

    List<PlaceCandidate> search(String keyword, Credentials credentials) {
        if (keyword.length() > 80) {
            throw new OnlinePlaceException(HttpStatus.BAD_REQUEST,
                    "INVALID_PARAMETER", "地点关键词最多 80 个字符", false);
        }
        JsonNode pois = request("/v5/place/text?keywords={value}&region={city}&city_limit=true"
                + "&show_fields=navi&page_size=12&key={key}", keyword, credentials);
        Map<String, PlaceCandidate> candidates = new LinkedHashMap<>();
        for (JsonNode poi : pois) {
            PlaceCandidate candidate = toCandidate(poi);
            if (candidate != null) {
                candidates.putIfAbsent(candidate.id(), candidate);
            }
        }
        return List.copyOf(candidates.values());
    }

    PlaceCandidate getById(String id, Credentials credentials) {
        String poiId = id.substring(ID_PREFIX.length());
        if (POI_ID.matcher(poiId).matches()) {
            JsonNode pois = request("/v5/place/detail?id={value}&show_fields=navi&key={key}", poiId, credentials);
            for (JsonNode poi : pois) {
                PlaceCandidate candidate = toCandidate(poi);
                if (candidate != null && id.equals(candidate.id())) {
                    return candidate;
                }
            }
        }
        throw new PlaceNotFoundException(id);
    }

    private JsonNode request(String uri, String value, Credentials credentials) {
        String key = credentials == null ? properties.getWebServiceKey() : credentials.amapKey();
        if (key == null || key.isBlank()) {
            throw new OnlinePlaceException(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_SEARCH_NOT_CONFIGURED",
                    "请先在服务配置验证高德 Web 服务 Key，或使用已配置的默认服务", false);
        }
        JsonNode response;
        try {
            response = client.get().uri(uri, Map.of("value", value, "city", CITY, "key", key))
                    .retrieve().body(JsonNode.class);
        } catch (ResourceAccessException error) {
            if (error.contains(HttpTimeoutException.class) || error.contains(java.net.SocketTimeoutException.class)) {
                throw new OnlinePlaceException(HttpStatus.GATEWAY_TIMEOUT, "PLACE_SEARCH_TIMEOUT",
                        "高德地点服务响应超时，请重试", true);
            }
            throw unavailable();
        } catch (RestClientResponseException error) {
            throw switch (error.getStatusCode().value()) {
                case 401, 403 -> unauthorized();
                case 429 -> rateLimited();
                default -> unavailable();
            };
        } catch (RestClientException error) {
            throw unavailable();
        }
        if (response == null) {
            throw unavailable();
        }
        if (!"1".equals(response.path("status").asText())) {
            String code = response.path("infocode").asText();
            if (QUOTA_CODES.contains(code)) {
                throw rateLimited();
            }
            if (AUTH_CODES.contains(code)) {
                throw unauthorized();
            }
            throw unavailable();
        }
        JsonNode pois = response.path("pois");
        if (!pois.isArray()) {
            throw unavailable();
        }
        return pois;
    }

    private static OnlinePlaceException unauthorized() {
        return new OnlinePlaceException(HttpStatus.BAD_GATEWAY, "PLACE_SEARCH_AUTH_FAILED",
                "高德地点服务拒绝了当前 Key，请检查 Web 服务 Key 的配置和权限", false);
    }

    private static OnlinePlaceException rateLimited() {
        return new OnlinePlaceException(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_SEARCH_RATE_LIMITED",
                "高德地点搜索达到频率或配额限制，请稍后重试", true);
    }

    private static OnlinePlaceException unavailable() {
        return new OnlinePlaceException(HttpStatus.BAD_GATEWAY, "PLACE_SEARCH_UNAVAILABLE",
                "高德地点服务暂不可用，请重试", true);
    }

    private static PlaceCandidate toCandidate(JsonNode poi) {
        String poiId = poi.path("id").asText("");
        String name = poi.path("name").asText("").strip();
        if (!CITY.equals(poi.path("cityname").asText()) || !POI_ID.matcher(poiId).matches() || name.isEmpty()) {
            return null;
        }
        String id = ID_PREFIX + poiId;
        GeoPosition display = position(id, poi.path("location"), "amap_poi");
        GeoPosition entrance = position(id, poi.path("navi").path("entr_location"), "amap_poi_entrance");
        GeoPosition routing = entrance == null ? display : entrance;
        String status = entrance != null ? "AMAP_POI_ENTRANCE"
                : display != null ? "AMAP_POI_LOCATION" : "UNVERIFIED";
        String district = poi.path("adname").asText("");
        String street = poi.path("address").asText("");
        String address = (district + " " + street).strip();
        return new PlaceCandidate(id, name, PlaceKind.CITY, poi.path("type").asText("城市地点"),
                List.of(), routing != null, routing == null ? "高德未提供可用的地点坐标" : null,
                status, display, routing, null, List.of("https://www.amap.com/place/" + poiId),
                address.isEmpty() ? null : address);
    }

    private static GeoPosition position(String id, JsonNode node, String role) {
        String[] parts = node.asText("").split(",", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            double longitude = Double.parseDouble(parts[0]);
            double latitude = Double.parseDouble(parts[1]);
            if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                    || longitude <= 0 || longitude > 180 || latitude <= 0 || latitude > 90) {
                return null;
            }
            return new GeoPosition(id + ":" + role, longitude, latitude, "GCJ-02", role);
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
