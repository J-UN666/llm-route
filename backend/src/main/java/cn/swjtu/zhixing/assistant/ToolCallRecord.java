package cn.swjtu.zhixing.assistant;

import java.util.Map;

/**
 * 一次实际执行的工具调用记录：保留模型给出的调用 ID、工具名、解析后的参数，
 * 以及 Java 方法实际返回值，便于验证工具确实被执行而不是模型编造。
 */
public record ToolCallRecord(
        String callId,
        String name,
        Map<String, Object> arguments,
        Object result) {
}
