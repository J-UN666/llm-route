package cn.swjtu.zhixing.assistant;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 模型接入配置；凭证只由服务端环境变量注入，不写入仓库或日志。
 *
 * <p>对应环境变量：{@code LLM_BASE_URL}、{@code LLM_API_KEY}、{@code LLM_MODEL}；
 * 连接与读取超时、工具调用轮次上限可通过 {@code LLM_CONNECT_TIMEOUT_MILLIS}、
 * {@code LLM_READ_TIMEOUT_MILLIS}、{@code LLM_MAX_TOOL_ROUNDS} 覆盖。</p>
 */
@Validated
@ConfigurationProperties(prefix = "assistant.llm")
public class AssistantProperties {

    /** 模型服务基础地址，例如 https://api.openai.com/v1 或 https://api.deepseek.com/v1。 */
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    @Min(1)
    private int connectTimeoutMillis = 5000;
    @Min(1)
    private int readTimeoutMillis = 30000;
    @Min(1)
    private int maxToolRounds = 3;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.trim();
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public void setReadTimeoutMillis(int readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public int getMaxToolRounds() {
        return maxToolRounds;
    }

    public void setMaxToolRounds(int maxToolRounds) {
        this.maxToolRounds = maxToolRounds;
    }

    /** 三项凭证齐全才认为模型接入已配置；缺失时接口返回 MODEL_NOT_CONFIGURED。 */
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    /** OpenAI 兼容的 Chat Completions 端点；允许 base-url 直接写到 /chat/completions。 */
    public String chatCompletionsUrl() {
        String url = baseUrl;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        return url + "/chat/completions";
    }
}
