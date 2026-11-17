package cn.swjtu.zhixing.assistant;

import java.util.List;

/** 一轮模型响应：最终文本（可能为空）与结构化工具调用（可能为空）。 */
public record ToolCallingResponse(
        String content,
        List<ModelToolCall> toolCalls) {
}
