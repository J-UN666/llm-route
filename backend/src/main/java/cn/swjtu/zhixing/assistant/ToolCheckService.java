package cn.swjtu.zhixing.assistant;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 最小真实模型工具调用闭环的编排：
 * 声明工具 → 模型返回 tool call → 按允许清单校验参数并执行 Java 方法 →
 * 用同一 callId 回传结构化结果 → 继续请求模型最终回答，最多 3 轮。
 *
 * <p>模型失败只影响本接口，与普通 {@code JourneyService} 查询链路完全分开。</p>
 */
@Service
public class ToolCheckService {

    static final String SYSTEM_PROMPT =
            "你是交大智行后端服务自检助手。需要服务名称、版本、Java 运行时时，必须调用 "
                    + "get_service_info 工具，不要编造；拿到工具结果后用中文简要回答。";

    private final ToolCallingClient toolCallingClient;
    private final ServiceInfoTool serviceInfoTool;
    private final AssistantProperties properties;
    private final ObjectMapper objectMapper;

    public ToolCheckService(ToolCallingClient toolCallingClient,
                            ServiceInfoTool serviceInfoTool,
                            AssistantProperties properties,
                            ObjectMapper objectMapper) {
        this.toolCallingClient = toolCallingClient;
        this.serviceInfoTool = serviceInfoTool;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ToolCheckResponse check(ToolCheckRequest request) {
        String requestId = AssistantIds.requestId();
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new AssistantApiException(HttpStatus.BAD_REQUEST,
                    AssistantErrorCodes.INVALID_PARAMETER, "message 不能为空", false, requestId);
        }
        if (!properties.isConfigured()) {
            throw new AssistantApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    AssistantErrorCodes.MODEL_NOT_CONFIGURED,
                    "模型未配置（需要 LLM_BASE_URL、LLM_API_KEY、LLM_MODEL）", false, requestId);
        }

        List<ToolCallingMessage> messages = new ArrayList<>();
        messages.add(new ToolCallingMessage("system", SYSTEM_PROMPT, null, null));
        messages.add(new ToolCallingMessage("user", request.message().trim(), null, null));
        List<ToolCallRecord> records = new ArrayList<>();
        int maxRounds = Math.max(1, properties.getMaxToolRounds());

        for (int round = 1; round <= maxRounds; round++) {
            ToolCallingResponse completion;
            try {
                completion = toolCallingClient.complete(new ToolCallingRequest(
                        properties.getModel(), List.copyOf(messages),
                        List.of(ServiceInfoTool.DEFINITION)));
            } catch (ToolCallingException ex) {
                throw new AssistantApiException(HttpStatus.valueOf(ex.status()), ex.code(),
                        ex.getMessage(), ex.retryable(), requestId);
            }
            List<ModelToolCall> calls = completion.toolCalls() == null
                    ? List.of() : completion.toolCalls();
            if (calls.isEmpty()) {
                String answer = completion.content() == null ? "" : completion.content().trim();
                if (answer.isEmpty()) {
                    throw new AssistantApiException(HttpStatus.BAD_GATEWAY,
                            AssistantErrorCodes.MODEL_UPSTREAM_ERROR,
                            "模型未返回最终回答", true, requestId);
                }
                return new ToolCheckResponse(requestId, properties.getModel(),
                        List.copyOf(records), answer);
            }
            messages.add(new ToolCallingMessage("assistant", completion.content(), null, calls));
            for (ModelToolCall call : calls) {
                executeToolCall(call, messages, records, requestId);
            }
        }
        throw new AssistantApiException(HttpStatus.BAD_GATEWAY,
                AssistantErrorCodes.MODEL_TOOL_ROUND_LIMIT_EXCEEDED,
                "模型工具调用轮次超过上限（" + maxRounds + " 轮）", false, requestId);
    }

    private void executeToolCall(ModelToolCall call,
                                 List<ToolCallingMessage> messages,
                                 List<ToolCallRecord> records,
                                 String requestId) {
        if (!ServiceInfoTool.NAME.equals(call.name())) {
            throw new AssistantApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    AssistantErrorCodes.INVALID_TOOL_NAME,
                    "模型调用了未声明的工具", false, requestId);
        }
        JsonNode arguments = parseArguments(call.argumentsJson(), requestId);
        ServiceInfo result;
        try {
            result = serviceInfoTool.execute(arguments);
        } catch (IllegalArgumentException ex) {
            throw new AssistantApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    AssistantErrorCodes.INVALID_TOOL_ARGUMENTS,
                    "工具参数不合法：" + ex.getMessage(), false, requestId);
        }
        records.add(new ToolCallRecord(call.callId(), call.name(),
                toArgumentsMap(arguments), result));
        messages.add(new ToolCallingMessage("tool", writeJson(result, requestId),
                call.callId(), null));
    }

    /**
     * 严格解析工具参数：必须恰好是一个 JSON 对象，不允许在对象之后附带任何内容
     * （例如 {@code "{} trailing"}、{@code "{} {\"verbose\":true}"}）。
     * 完整校验在工具执行前完成，非法参数不会触发 Java 方法。
     */
    private JsonNode parseArguments(String argumentsJson, String requestId) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try (JsonParser parser = objectMapper.createParser(argumentsJson)) {
            JsonNode node = objectMapper.readTree(parser);
            if (node == null || !node.isObject()) {
                throw invalidArguments("工具参数必须是 JSON 对象", requestId);
            }
            if (parser.nextToken() != null) {
                throw invalidArguments("工具参数必须是单个 JSON 对象，不能包含多余内容", requestId);
            }
            return node;
        } catch (IOException ex) {
            throw invalidArguments("工具参数不是合法 JSON", requestId);
        }
    }

    private AssistantApiException invalidArguments(String message, String requestId) {
        return new AssistantApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                AssistantErrorCodes.INVALID_TOOL_ARGUMENTS, message, false, requestId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toArgumentsMap(JsonNode arguments) {
        if (arguments == null || arguments.isNull() || arguments.isEmpty()) {
            return Map.of();
        }
        return objectMapper.convertValue(arguments, LinkedHashMap.class);
    }

    private String writeJson(ServiceInfo result, String requestId) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new AssistantApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    AssistantErrorCodes.SERVER_ERROR, "工具结果序列化失败", true, requestId);
        }
    }
}
