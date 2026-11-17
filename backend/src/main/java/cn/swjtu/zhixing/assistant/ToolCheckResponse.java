package cn.swjtu.zhixing.assistant;

import java.util.List;

/** POST /api/assistant/tool-check 成功响应。 */
public record ToolCheckResponse(
        String requestId,
        String model,
        List<ToolCallRecord> toolCalls,
        String answer) {
}
