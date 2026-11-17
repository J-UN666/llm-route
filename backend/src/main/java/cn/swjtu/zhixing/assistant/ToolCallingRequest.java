package cn.swjtu.zhixing.assistant;

import java.util.List;

/** 一轮模型请求：模型名、完整消息历史与声明工具。 */
public record ToolCallingRequest(
        String model,
        List<ToolCallingMessage> messages,
        List<ToolDefinition> tools) {
}
