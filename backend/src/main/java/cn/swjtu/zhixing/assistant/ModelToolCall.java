package cn.swjtu.zhixing.assistant;

/**
 * 模型返回的一次结构化工具调用。
 *
 * @param callId        模型生成的调用 ID，回传结果时必须原样携带
 * @param name          工具名
 * @param argumentsJson 模型给出的参数 JSON 字符串；空对象为 {@code {}}
 */
public record ModelToolCall(
        String callId,
        String name,
        String argumentsJson) {
}
