package cn.swjtu.zhixing.serviceconfig;

import static cn.swjtu.zhixing.serviceconfig.PersonalServices.OWNER_HEADER;

import cn.swjtu.zhixing.assistant.ToolCallingClient;
import cn.swjtu.zhixing.assistant.ToolCallingException;
import cn.swjtu.zhixing.serviceconfig.PersonalServices.Credentials;
import cn.swjtu.zhixing.serviceconfig.PersonalServices.Created;
import cn.swjtu.zhixing.serviceconfig.PersonalServices.Session;
import cn.swjtu.zhixing.transit.TransitPoint;
import cn.swjtu.zhixing.transit.TransitErrorCode;
import cn.swjtu.zhixing.transit.TransitPreference;
import cn.swjtu.zhixing.transit.TransitProvider;
import cn.swjtu.zhixing.transit.TransitSearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("!demo")
@RestController
@RequestMapping("/api/service-config")
class ServiceConfigController {
    private static final List<String> MODELS = List.of("deepseek-flash", "deepseek-v4-pro");
    private static final TransitPoint EAST_GATE =
            new TransitPoint("east-gate", 103.993572, 30.769141, "GCJ-02");
    private static final TransitPoint TIANFU =
            new TransitPoint("tianfu", 104.0668, 30.6575, "GCJ-02");

    private final PersonalServices services;
    private final TransitProvider transit;
    private final ToolCallingClient model;

    ServiceConfigController(PersonalServices services, TransitProvider transit, ToolCallingClient model) {
        this.services = services;
        this.transit = transit;
        this.model = model;
    }

    record ModelOptions(String provider, List<String> models) {}

    record Status(Instant expiresAt, boolean amapVerified, boolean modelVerified, String model) {}

    @GetMapping("/options")
    ResponseEntity<ModelOptions> options(HttpServletRequest request) {
        PersonalServices.requireHttps(request);
        return uncached(new ModelOptions("DeepSeek", MODELS));
    }

    @PostMapping("/sessions")
    ResponseEntity<Created> create(HttpServletRequest request) {
        PersonalServices.requireHttps(request);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(services.create());
    }

    @GetMapping
    ResponseEntity<Status> status(@RequestHeader(name = OWNER_HEADER, required = false) String token,
                                  HttpServletRequest request) {
        PersonalServices.requireHttps(request);
        return uncached(status(services.require(token)));
    }

    @PutMapping(path = "/amap", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Status> updateAmap(@RequestHeader(name = OWNER_HEADER, required = false) String token,
                                      @RequestBody JsonNode body, HttpServletRequest request) {
        PersonalServices.requireHttps(request);
        Session before = services.require(token);
        String key = field(body, "key");
        if (body.size() != 1 || !key.matches("[A-Za-z0-9]{32}")) {
            throw invalid("请输入有效格式的高德 Web 服务 Key");
        }
        OffsetDateTime departure = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(10);
        var result = transit.searchWithKey(
                new TransitSearchRequest(EAST_GATE, TIANFU, departure, TransitPreference.RECOMMENDED), key);
        var code = result.errorCode();
        if (code != null && code != TransitErrorCode.NO_ROUTE) {
            throw switch (code) {
                case AMAP_KEY_PLATFORM_MISMATCH -> invalid("该 Key 的平台不符，请使用高德 Web 服务 Key");
                case AMAP_AUTHORIZATION_ERROR -> invalid("高德 Key 无效、已失效或无公交规划权限");
                case AMAP_QUOTA_ERROR -> new ServiceConfigException(HttpStatus.TOO_MANY_REQUESTS,
                        "PROVIDER_RATE_LIMITED", "高德 Key 额度耗尽或请求过于频繁", true);
                case NETWORK_TIMEOUT -> new ServiceConfigException(HttpStatus.GATEWAY_TIMEOUT,
                        "PROVIDER_TIMEOUT", "高德验证超时", true);
                default -> new ServiceConfigException(HttpStatus.BAD_GATEWAY,
                        "PROVIDER_ERROR", "高德验证暂时不可用，请稍后重试", true);
            };
        }
        var old = before.credentials();
        return uncached(status(services.replace(token, before, new Credentials(key, old.modelKey(), old.model()))));
    }

    @PutMapping(path = "/model", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Status> updateModel(@RequestHeader(name = OWNER_HEADER, required = false) String token,
                                       @RequestBody JsonNode body, HttpServletRequest request) {
        PersonalServices.requireHttps(request);
        Session before = services.require(token);
        String key = field(body, "key");
        String name = field(body, "model");
        if (body.size() != 2 || !MODELS.contains(name) || !key.matches("[A-Za-z0-9_-]{8,160}")) {
            throw invalid("请选择支持的模型并填写有效格式的 API Key");
        }
        try {
            model.verifyPersonalKey(key, name);
        } catch (ToolCallingException ex) {
            throw new ServiceConfigException(HttpStatus.valueOf(ex.status()), ex.code(), ex.getMessage(), ex.retryable());
        }
        return uncached(status(services.replace(token, before, new Credentials(before.credentials().amapKey(), key, name))));
    }

    @DeleteMapping("/{service:amap|model}")
    ResponseEntity<Status> revokeKey(@PathVariable String service,
                                      @RequestHeader(name = OWNER_HEADER, required = false) String token,
                                      HttpServletRequest request) {
        PersonalServices.requireHttps(request);
        Session before = services.require(token);
        Credentials old = before.credentials();
        Credentials updated = service.equals("amap")
                ? new Credentials(null, old.modelKey(), old.model())
                : new Credentials(old.amapKey(), null, null);
        return uncached(status(services.replace(token, before, updated)));
    }

    @DeleteMapping
    ResponseEntity<Void> revoke(@RequestHeader(name = OWNER_HEADER, required = false) String token,
                                 HttpServletRequest request) {
        PersonalServices.requireHttps(request);
        services.revoke(token);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ServiceConfigErrors.ErrorBody> malformedBody() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(new ServiceConfigErrors.ErrorBody("INVALID_SERVICE_CONFIG", "无效的服务配置请求", false));
    }

    private static String field(JsonNode body, String name) {
        return body.path(name).isTextual() ? body.path(name).textValue() : "";
    }

    private static Status status(Session session) {
        var keys = session.credentials();
        return new Status(session.expiresAt(), keys.amapKey() != null, keys.modelKey() != null, keys.model());
    }

    private static ServiceConfigException invalid(String message) {
        return new ServiceConfigException(HttpStatus.BAD_REQUEST, "INVALID_SERVICE_CONFIG", message, false);
    }

    private static <T> ResponseEntity<T> uncached(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
