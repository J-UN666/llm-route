package cn.swjtu.zhixing.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.env.MockEnvironment;

/** 编排层：工具分发、参数校验、callId 回传、轮次上限与错误码。 */
class ToolCheckServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AssistantProperties properties;
    private ServiceInfoTool serviceInfoTool;
    private StubToolCallingClient client;
    private ToolCheckService service;

    @BeforeEach
    void setUp() {
        properties = configuredProperties();
        Properties build = new Properties();
        build.setProperty("version", "0.1.0-SNAPSHOT");
        BuildProperties buildProperties = new BuildProperties(build);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "llm-route-backend");
        serviceInfoTool = spy(new ServiceInfoTool(environment, buildProperties));
        client = new StubToolCallingClient();
        service = new ToolCheckService(client, serviceInfoTool, properties, objectMapper);
    }

    private AssistantProperties configuredProperties() {
        AssistantProperties configured = new AssistantProperties();
        configured.setBaseUrl("http://127.0.0.1:9/v1");
        configured.setApiKey("test-key");
        configured.setModel("test-model");
        return configured;
    }

    @Test
    void executesDeclaredToolAndFeedsResultBackWithSameCallId() {
        client.enqueue(toolCallResponse("call_1", "get_service_info", "{}"));
        client.enqueue(new ToolCallingResponse("服务 llm-route-backend，版本 0.1.0-SNAPSHOT。", List.of()));

        ToolCheckResponse response = service.check(new ToolCheckRequest("请调用工具查看当前服务信息"));

        assertThat(response.requestId()).startsWith("tc-").hasSize(23);
        assertThat(response.model()).isEqualTo("test-model");
        assertThat(response.answer()).contains("llm-route-backend");
        assertThat(response.toolCalls()).hasSize(1);
        ToolCallRecord record = response.toolCalls().get(0);
        assertThat(record.callId()).isEqualTo("call_1");
        assertThat(record.name()).isEqualTo("get_service_info");
        assertThat(record.arguments()).isEmpty();
        assertThat(record.result()).isInstanceOf(ServiceInfo.class);
        ServiceInfo info = (ServiceInfo) record.result();
        assertThat(info.applicationName()).isEqualTo("llm-route-backend");
        assertThat(info.applicationVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(info.businessTimeZone()).isEqualTo("Asia/Shanghai");

        assertThat(client.requests).hasSize(2);
        List<ToolCallingMessage> followUp = client.requests.get(1).messages();
        assertThat(followUp).hasSize(4);
        assertThat(followUp.get(2).role()).isEqualTo("assistant");
        assertThat(followUp.get(2).toolCalls()).extracting(ModelToolCall::callId).containsExactly("call_1");
        ToolCallingMessage toolMessage = followUp.get(3);
        assertThat(toolMessage.role()).isEqualTo("tool");
        assertThat(toolMessage.toolCallId()).isEqualTo("call_1");
        assertThat(toolMessage.content()).contains("llm-route-backend").contains("0.1.0-SNAPSHOT");
        assertThat(client.requests.get(1).tools()).hasSize(1);
        assertThat(client.requests.get(1).tools().get(0).name()).isEqualTo("get_service_info");
        verify(serviceInfoTool, times(1)).execute(any(JsonNode.class));
    }

    @Test
    void rejectsUndeclaredToolName() {
        client.enqueue(toolCallResponse("call_x", "rm_rf", "{}"));

        assertThatThrownBy(() -> service.check(new ToolCheckRequest("请调用工具")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("INVALID_TOOL_NAME");
                    assertThat(ex.status().value()).isEqualTo(422);
                    assertThat(ex.retryable()).isFalse();
                });
        verify(serviceInfoTool, never()).execute(any(JsonNode.class));
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void rejectsInvalidToolArguments() {
        client.enqueue(toolCallResponse("call_1", "get_service_info", "{\"verbose\":true}"));

        assertThatThrownBy(() -> service.check(new ToolCheckRequest("请调用工具")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("INVALID_TOOL_ARGUMENTS");
                    assertThat(ex.status().value()).isEqualTo(422);
                });
    }

    @Test
    void rejectsMalformedArgumentsJson() {
        client.enqueue(toolCallResponse("call_1", "get_service_info", "not-json"));

        assertThatThrownBy(() -> service.check(new ToolCheckRequest("请调用工具")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("INVALID_TOOL_ARGUMENTS"));
    }

    @Test
    void rejectsArgumentsWithTrailingText() {
        client.enqueue(toolCallResponse("call_1", "get_service_info", "{} trailing"));

        assertThatThrownBy(() -> service.check(new ToolCheckRequest("请调用工具")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("INVALID_TOOL_ARGUMENTS");
                    assertThat(ex.status().value()).isEqualTo(422);
                });
        verify(serviceInfoTool, never()).execute(any(JsonNode.class));
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void rejectsArgumentsWithTrailingJsonObject() {
        client.enqueue(toolCallResponse("call_1", "get_service_info", "{}{\"verbose\":true}"));

        assertThatThrownBy(() -> service.check(new ToolCheckRequest("请调用工具")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("INVALID_TOOL_ARGUMENTS");
                    assertThat(ex.status().value()).isEqualTo(422);
                });
        verify(serviceInfoTool, never()).execute(any(JsonNode.class));
        assertThat(client.requests).hasSize(1);
    }

    @Test
    void allowsTrailingWhitespaceAfterArgumentsObject() {
        client.enqueue(toolCallResponse("call_1", "get_service_info", "{}   "));
        client.enqueue(new ToolCallingResponse("服务信息已读取。", List.of()));

        ToolCheckResponse response = service.check(new ToolCheckRequest("请调用工具查看当前服务信息"));

        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).arguments()).isEmpty();
        verify(serviceInfoTool, times(1)).execute(any(JsonNode.class));
    }

    @Test
    void returnsModelNotConfiguredWithoutCallingClient() {
        service = new ToolCheckService(client, serviceInfoTool, new AssistantProperties(), objectMapper);

        assertThatThrownBy(() -> service.check(new ToolCheckRequest("请调用工具")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("MODEL_NOT_CONFIGURED");
                    assertThat(ex.status().value()).isEqualTo(503);
                    assertThat(ex.requestId()).startsWith("tc-");
                });
        assertThat(client.requests).isEmpty();
    }

    @Test
    void rejectsBlankMessage() {
        assertThatThrownBy(() -> service.check(new ToolCheckRequest("   ")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("INVALID_PARAMETER");
                    assertThat(ex.status().value()).isEqualTo(400);
                });
        assertThat(client.requests).isEmpty();
    }

    @Test
    void failsAfterMaxToolRounds() {
        for (int i = 0; i < 5; i++) {
            client.enqueue(toolCallResponse("call_" + i, "get_service_info", "{}"));
        }

        assertThatThrownBy(() -> service.check(new ToolCheckRequest("请调用工具")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("MODEL_TOOL_ROUND_LIMIT_EXCEEDED");
                    assertThat(ex.status().value()).isEqualTo(502);
                });
        assertThat(client.requests).hasSize(3);
        verify(serviceInfoTool, times(3)).execute(any(JsonNode.class));
    }

    @Test
    void executesMultipleToolCallsFromOneRound() {
        client.enqueue(new ToolCallingResponse(null, List.of(
                new ModelToolCall("call_a", "get_service_info", "{}"),
                new ModelToolCall("call_b", "get_service_info", "{}"))));
        client.enqueue(new ToolCallingResponse("两次查询结果一致。", List.of()));

        ToolCheckResponse response = service.check(new ToolCheckRequest("请调用工具两次"));

        assertThat(response.toolCalls()).extracting(ToolCallRecord::callId)
                .containsExactly("call_a", "call_b");
        assertThat(client.requests.get(1).messages()).hasSize(5);
    }

    @Test
    void propagatesTimeoutAsModelTimeout() {
        client.failure = new ToolCallingException(504, "MODEL_TIMEOUT", "模型请求超时", true);

        assertThatThrownBy(() -> service.check(new ToolCheckRequest("请调用工具")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("MODEL_TIMEOUT");
                    assertThat(ex.status().value()).isEqualTo(504);
                    assertThat(ex.retryable()).isTrue();
                    assertThat(ex.requestId()).startsWith("tc-");
                });
    }

    @Test
    void failsWhenModelReturnsNoContentAndNoToolCall() {
        client.enqueue(new ToolCallingResponse("  ", List.of()));

        assertThatThrownBy(() -> service.check(new ToolCheckRequest("请调用工具")))
                .isInstanceOfSatisfying(AssistantApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("MODEL_UPSTREAM_ERROR");
                    assertThat(ex.status().value()).isEqualTo(502);
                });
    }

    private ToolCallingResponse toolCallResponse(String callId, String name, String arguments) {
        return new ToolCallingResponse(null, List.of(new ModelToolCall(callId, name, arguments)));
    }

    /** 按队列返回模型响应，并记录每次请求，便于断言调用次数与回传格式。 */
    private static final class StubToolCallingClient implements ToolCallingClient {
        private final List<ToolCallingResponse> responses = new ArrayList<>();
        private final List<ToolCallingRequest> requests = new ArrayList<>();
        private int index;
        private ToolCallingException failure;

        void enqueue(ToolCallingResponse response) {
            responses.add(response);
        }

        @Override
        public ToolCallingResponse complete(ToolCallingRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            if (index >= responses.size()) {
                throw new ToolCallingException(502, "MODEL_UPSTREAM_ERROR", "无更多重放响应", true);
            }
            return responses.get(index++);
        }
    }
}
