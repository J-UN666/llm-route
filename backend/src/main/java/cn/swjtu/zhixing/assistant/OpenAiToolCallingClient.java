package cn.swjtu.zhixing.assistant;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI 兼容 Chat Completions 工具调用客户端。
 *
 * <p>请求体只声明 function tools，并解析 choices[0].message.tool_calls 或最终 content。
 * 托管配置来自部署环境，个人凭据只会发送到固定的官方端点。
 */
@Component
@Profile("!demo")
public class OpenAiToolCallingClient implements ToolCallingClient {
    private static final String PERSONAL_ENDPOINT = "https://api.deepseek.com/chat/completions";

    private final RestClient restClient;
    private final AssistantProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public OpenAiToolCallingClient(RestClient.Builder restClientBuilder,
                                   AssistantProperties properties,
                                   ObjectMapper objectMapper) {
        this(buildRestClient(restClientBuilder, properties), properties, objectMapper);
    }

    OpenAiToolCallingClient(RestClient restClient,
                            AssistantProperties properties,
                            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolCallingResponse complete(ToolCallingRequest request) {
        if (!properties.isConfigured()) {
            throw new ToolCallingException(503, AssistantErrorCodes.MODEL_NOT_CONFIGURED,
                    "模型未配置（LLM_BASE_URL、LLM_API_KEY、LLM_MODEL）", false);
        }
        return complete(request, properties.chatCompletionsUrl(), properties.getApiKey(), false, false);
    }

    @Override
    public ToolCallingResponse completeWithKey(ToolCallingRequest request, String key) {
        return complete(request, PERSONAL_ENDPOINT, key, true, false);
    }

    @Override
    public void verifyPersonalKey(String key, String model) {
        var request = new ToolCallingRequest(model,
                List.of(new ToolCallingMessage("user", "Reply OK.", null, null)), List.of(), true);
        var response = complete(request, PERSONAL_ENDPOINT, key, true, true);
        if (response.content() == null || response.content().isBlank()) {
            throw upstreamError("模型验证未返回文本");
        }
    }

    private ToolCallingResponse complete(ToolCallingRequest request, String endpoint, String key,
                                         boolean personal, boolean probe) {
        String payload = buildPayload(request, endpoint, probe);
        String raw;
        try {
            raw = restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) {
                throw new ToolCallingException(504, AssistantErrorCodes.MODEL_TIMEOUT, "模型请求超时", true);
            }
            throw new ToolCallingException(502, AssistantErrorCodes.MODEL_UPSTREAM_ERROR, "模型网络请求失败", true);
        } catch (RestClientResponseException exception) {
            if (personal) {
                throw switch (exception.getStatusCode().value()) {
                    case 401, 403 -> new ToolCallingException(422, "MODEL_KEY_INVALID", "DeepSeek Key 无效或已失效", false);
                    case 402 -> new ToolCallingException(402, "MODEL_QUOTA_EXHAUSTED", "DeepSeek 账户余额不足", false);
                    case 429 -> new ToolCallingException(429, "MODEL_RATE_LIMITED", "DeepSeek 请求过于频繁，请稍后重试", true);
                    case 408, 504 -> new ToolCallingException(504, AssistantErrorCodes.MODEL_TIMEOUT, "模型请求超时", true);
                    default -> upstreamError("DeepSeek 服务请求失败");
                };
            }
            throw new ToolCallingException(502, AssistantErrorCodes.MODEL_UPSTREAM_ERROR,
                    "模型服务返回错误（HTTP " + exception.getStatusCode().value() + "）", true);
        } catch (RestClientException exception) {
            throw new ToolCallingException(502, AssistantErrorCodes.MODEL_UPSTREAM_ERROR, "模型请求失败", true);
        }
        return parseResponse(raw, request.strictToolArguments());
    }

    private String buildPayload(ToolCallingRequest request, String endpoint, boolean probe) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        ArrayNode messages = body.putArray("messages");
        for (ToolCallingMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            if (message.content() == null) {
                node.putNull("content");
            } else {
                node.put("content", message.content());
            }
            if ("tool".equals(message.role()) && message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ModelToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.callId());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.argumentsJson());
                }
            }
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode toolNode = tools.addObject();
                toolNode.put("type", "function");
                ObjectNode function = toolNode.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", objectMapper.valueToTree(tool.parameters()));
            }
            body.put("tool_choice", "auto");
        }
        body.put("temperature", 0);
        if (probe) {
            body.put("max_tokens", 8);
        }
        if ("api.deepseek.com".equalsIgnoreCase(URI.create(endpoint).getHost())) {
            body.putObject("thinking").put("type", "disabled");
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new ToolCallingException(500, AssistantErrorCodes.SERVER_ERROR, "构造模型请求失败", false);
        }
    }

    private ToolCallingResponse parseResponse(String raw, boolean strict) {
        if (raw == null || raw.isBlank()) {
            throw upstreamError("模型响应为空");
        }
        JsonNode root;
        try {
            root = strict
                    ? objectMapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw)
                    : objectMapper.readTree(raw);
        } catch (JsonProcessingException exception) {
            throw upstreamError("模型响应不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw upstreamError("模型响应结构错误");
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw upstreamError("模型响应缺少 choices");
        }
        JsonNode message = choices.get(0).path("message");
        if (!message.isObject()) {
            throw upstreamError("模型响应缺少 message");
        }
        String content = textualContent(message.get("content"));
        List<ModelToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                String callId = text(call, "id");
                JsonNode function = call.path("function");
                String name = text(function, "name");
                if (callId == null || callId.isBlank() || name == null || name.isBlank()) {
                    throw upstreamError("模型工具调用结构错误");
                }
                JsonNode arguments = function.get("arguments");
                if (strict && (arguments == null || !arguments.isTextual())) {
                    throw upstreamError("模型工具 arguments 必须是 JSON 字符串");
                }
                String argumentsJson = arguments == null
                        ? "{}"
                        : (arguments.isTextual() ? arguments.asText() : arguments.toString());
                calls.add(new ModelToolCall(callId, name, argumentsJson));
            }
        }
        return new ToolCallingResponse(content, List.copyOf(calls));
    }

    private static String textualContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : content) {
                if (part.isTextual()) {
                    builder.append(part.asText());
                } else if (part.hasNonNull("text")) {
                    builder.append(part.path("text").asText());
                }
            }
            return builder.length() == 0 ? null : builder.toString();
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static ToolCallingException upstreamError(String message) {
        return new ToolCallingException(502, AssistantErrorCodes.MODEL_UPSTREAM_ERROR, message, true);
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static RestClient buildRestClient(RestClient.Builder builder, AssistantProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        return builder.requestFactory(requestFactory).build();
    }
}
