package cn.swjtu.zhixing.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 用 JDK HttpServer 重放模型响应，验证 OpenAI 兼容协议、错误映射与超时配置。 */
class OpenAiToolCallingClientTests {

    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String TOOL_CALL_JSON = """
            {"choices":[{"message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"call_1","type":"function",
            "function":{"name":"get_service_info","arguments":"{}"}}]}}]}
            """;
    private static final String ANSWER_JSON = """
            {"choices":[{"message":{"role":"assistant","content":"服务 llm-route-backend"}}]}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int responseStatus = 200;
    private volatile String responseBody = TOOL_CALL_JSON;
    private volatile long responseDelayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CHAT_PATH, this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        lastPath.set(exchange.getRequestURI().getPath());
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (responseDelayMillis > 0) {
            try {
                Thread.sleep(responseDelayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private AssistantProperties properties() {
        AssistantProperties properties = new AssistantProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        properties.setConnectTimeoutMillis(1000);
        properties.setReadTimeoutMillis(5000);
        return properties;
    }

    private OpenAiToolCallingClient client(AssistantProperties properties) {
        return new OpenAiToolCallingClient(RestClient.builder(), properties, objectMapper);
    }

    private ToolCallingRequest request() {
        return new ToolCallingRequest("test-model", List.of(
                new ToolCallingMessage("system", "system prompt", null, null),
                new ToolCallingMessage("user", "请调用工具查看当前服务信息", null, null)),
                List.of(ServiceInfoTool.DEFINITION));
    }

    @Test
    void sendsToolDeclarationAndParsesToolCall() throws IOException {
        ToolCallingResponse response = client(properties()).complete(request());

        assertThat(lastPath.get()).isEqualTo(CHAT_PATH);
        assertThat(lastAuthorization.get()).isEqualTo("Bearer test-key");
        assertThat(response.content()).isNull();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).callId()).isEqualTo("call_1");
        assertThat(response.toolCalls().get(0).name()).isEqualTo("get_service_info");
        assertThat(response.toolCalls().get(0).argumentsJson()).isEqualTo("{}");

        JsonNode payload = objectMapper.readTree(lastRequestBody.get());
        assertThat(payload.path("model").asText()).isEqualTo("test-model");
        assertThat(payload.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(payload.path("messages")).hasSize(2);
        assertThat(payload.path("messages").get(1).path("content").asText())
                .isEqualTo("请调用工具查看当前服务信息");
        JsonNode tool = payload.path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("function").path("name").asText()).isEqualTo("get_service_info");
        assertThat(tool.path("function").path("parameters").path("type").asText()).isEqualTo("object");
        assertThat(tool.path("function").path("parameters").path("properties").isEmpty()).isTrue();
    }

    @Test
    void parsesFinalAnswerWithoutToolCalls() {
        responseBody = ANSWER_JSON;

        ToolCallingResponse response = client(properties()).complete(request());

        assertThat(response.content()).isEqualTo("服务 llm-route-backend");
        assertThat(response.toolCalls()).isEmpty();
    }

    @Test
    void supportsBaseUrlWithoutTrailingSlashEquivalence() {
        AssistantProperties properties = properties();
        properties.setBaseUrl(properties.getBaseUrl() + "/");

        ToolCallingResponse response = client(properties).complete(request());

        assertThat(response.toolCalls()).hasSize(1);
        assertThat(lastPath.get()).isEqualTo(CHAT_PATH);
    }

    @Test
    void mapsHttpErrorToUpstreamErrorWithoutLeakingKey() {
        responseStatus = 401;
        responseBody = "{\"error\":{\"message\":\"invalid api key\"}}";

        assertThatThrownBy(() -> client(properties()).complete(request()))
                .isInstanceOfSatisfying(ToolCallingException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_UPSTREAM_ERROR");
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.retryable()).isTrue();
                    assertThat(error.getMessage()).contains("HTTP 401").doesNotContain("test-key");
                });
    }

    @Test
    void mapsMalformedResponseToUpstreamError() {
        responseBody = "not-json";

        assertThatThrownBy(() -> client(properties()).complete(request()))
                .isInstanceOfSatisfying(ToolCallingException.class, error ->
                        assertThat(error.code()).isEqualTo("MODEL_UPSTREAM_ERROR"));
    }

    @Test
    void mapsMissingChoicesToUpstreamError() {
        responseBody = "{\"id\":\"x\"}";

        assertThatThrownBy(() -> client(properties()).complete(request()))
                .isInstanceOfSatisfying(ToolCallingException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_UPSTREAM_ERROR");
                    assertThat(error.getMessage()).contains("choices");
                });
    }

    @Test
    void mapsReadTimeoutToModelTimeout() {
        responseDelayMillis = 800;
        AssistantProperties properties = properties();
        properties.setReadTimeoutMillis(150);

        assertThatThrownBy(() -> client(properties).complete(request()))
                .isInstanceOfSatisfying(ToolCallingException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_TIMEOUT");
                    assertThat(error.status()).isEqualTo(504);
                    assertThat(error.retryable()).isTrue();
                });
    }

    @Test
    void rejectsUnconfiguredPropertiesBeforeAnyRequest() {
        assertThatThrownBy(() -> client(new AssistantProperties()).complete(request()))
                .isInstanceOfSatisfying(ToolCallingException.class, error -> {
                    assertThat(error.code()).isEqualTo("MODEL_NOT_CONFIGURED");
                    assertThat(error.status()).isEqualTo(503);
                });
        assertThat(hits.get()).isZero();
    }
}
