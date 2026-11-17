package cn.swjtu.zhixing.assistant;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 模型凭证缺失时返回可识别错误码，不需要发起任何外部请求。 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute",
        "assistant.llm.base-url=",
        "assistant.llm.api-key=",
        "assistant.llm.model="
})
@AutoConfigureMockMvc
class ToolCheckNotConfiguredTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsModelNotConfigured() throws Exception {
        mockMvc.perform(post("/api/assistant/tool-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"请调用工具查看当前服务信息\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.requestId", matchesPattern("tc-[0-9a-f]{20}")));
    }
}
