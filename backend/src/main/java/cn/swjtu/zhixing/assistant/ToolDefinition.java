package cn.swjtu.zhixing.assistant;

import java.util.Map;

/** 声明给模型的工具定义（OpenAI 兼容 function calling 的 function 部分）。 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters) {
}
