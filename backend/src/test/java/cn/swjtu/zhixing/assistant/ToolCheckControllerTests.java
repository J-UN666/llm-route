package cn.swjtu.zhixing.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP 边界：成功响应结构、错误码映射与 requestId 可追溯。 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute",
        "assistant.llm.base-url=http://127.0.0.1:9/v1",
        "assistant.llm.api-key=test-key",
        "assistant.llm.model=test-model"
})
@AutoConfigureMockMvc
class ToolCheckControllerTests {

    private static final String ENDPOINT = "/api/assistant/tool-check";
    private static final String MESSAGE_JSON = "{\"message\":\"请调用工具查看当前服务信息\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ToolCallingClient toolCallingClient;

    @BeforeEach
    void resetClient() {
        reset(toolCallingClient);
    }

    private ToolCallingResponse toolCall(String callId, String name, String arguments) {
        return new ToolCallingResponse(null, List.of(new ModelToolCall(callId, name, arguments)));
    }

    @Test
    void returnsRequestIdModelToolCallsAndAnswer() throws Exception {
        when(toolCallingClient.complete(any()))
                .thenReturn(toolCall(toolCallResponseId(), "get_service_info", "{}"))
                .thenReturn(new ToolCallingResponse("服务名称 llm-route-backend，版本与 Java 运行时见工具结果。", List.of()));

        MvcResult result = mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(MESSAGE_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId", matchesPattern("tc-[0-9a-f]{20}")))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.toolCalls").isArray())
                .andExpect(jsonPath("$.toolCalls.length()").value(1))
                .andExpect(jsonPath("$.toolCalls[0].callId").value("call_1"))
                .andExpect(jsonPath("$.toolCalls[0].name").value("get_service_info"))
                .andExpect(jsonPath("$.toolCalls[0].result.applicationName").value("llm-route-backend"))
                .andExpect(jsonPath("$.toolCalls[0].result.applicationVersion").isNotEmpty())
                .andExpect(jsonPath("$.toolCalls[0].result.javaVersion").isNotEmpty())
                .andExpect(jsonPath("$.toolCalls[0].result.businessTimeZone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.answer").value(
                        "服务名称 llm-route-backend，版本与 Java 运行时见工具结果。"))
                .andReturn();

        JsonNode responseBody = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode arguments = responseBody.path("toolCalls").get(0).path("arguments");
        assertThat(arguments.isObject()).isTrue();
        assertThat(arguments.size()).isZero();

        ArgumentCaptor<ToolCallingRequest> captor = ArgumentCaptor.forClass(ToolCallingRequest.class);
        verify(toolCallingClient, times(2)).complete(captor.capture());
        List<ToolCallingRequest> requests = captor.getAllValues();
        assertThat(requests.get(0).messages()).hasSize(2);
        List<ToolCallingMessage> secondRound = requests.get(1).messages();
        assertThat(secondRound).hasSize(4);
        assertThat(secondRound.get(2).toolCalls()).hasSize(1);
        JsonNode toolResult = objectMapper.readTree(secondRound.get(3).content());
        assertThat(toolResult.path("applicationName").asText()).isEqualTo("llm-route-backend");
        assertThat(secondRound.get(3).toolCallId()).isEqualTo("call_1");
    }

    @Test
    void rejectsUndeclaredToolName() throws Exception {
        when(toolCallingClient.complete(any()))
                .thenReturn(toolCall("call_1", "delete_everything", "{}"));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(MESSAGE_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TOOL_NAME"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.requestId", matchesPattern("tc-[0-9a-f]{20}")));
    }

    @Test
    void rejectsInvalidToolArguments() throws Exception {
        when(toolCallingClient.complete(any()))
                .thenReturn(toolCall("call_1", "get_service_info", "{\"detail\":true}"));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(MESSAGE_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TOOL_ARGUMENTS"));
    }

    @Test
    void rejectsArgumentsWithTrailingText() throws Exception {
        when(toolCallingClient.complete(any()))
                .thenReturn(toolCall("call_1", "get_service_info", "{} trailing"));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(MESSAGE_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TOOL_ARGUMENTS"))
                .andExpect(jsonPath("$.retryable").value(false));
        verify(toolCallingClient, times(1)).complete(any());
    }

    @Test
    void rejectsArgumentsWithTrailingJsonObject() throws Exception {
        when(toolCallingClient.complete(any()))
                .thenReturn(toolCall("call_1", "get_service_info", "{}{\"verbose\":true}"));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(MESSAGE_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TOOL_ARGUMENTS"));
        verify(toolCallingClient, times(1)).complete(any());
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.requestId", matchesPattern("tc-[0-9a-f]{20}")));
    }

    @Test
    void rejectsMalformedJsonBody() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void mapsModelTimeoutToGatewayTimeout() throws Exception {
        when(toolCallingClient.complete(any()))
                .thenThrow(new ToolCallingException(504, "MODEL_TIMEOUT", "模型请求超时", true));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(MESSAGE_JSON))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("MODEL_TIMEOUT"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void mapsUpstreamErrorToBadGateway() throws Exception {
        when(toolCallingClient.complete(any()))
                .thenThrow(new ToolCallingException(502, "MODEL_UPSTREAM_ERROR", "模型服务返回错误", true));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(MESSAGE_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_UPSTREAM_ERROR"));
    }

    @Test
    void stopsAfterThreeToolRounds() throws Exception {
        when(toolCallingClient.complete(any()))
                .thenReturn(toolCall(toolCallResponseId(), "get_service_info", "{}"));

        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(MESSAGE_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_TOOL_ROUND_LIMIT_EXCEEDED"));
        verify(toolCallingClient, times(3)).complete(any());
    }

    private static String toolCallResponseId() {
        return "call_1";
    }
}
