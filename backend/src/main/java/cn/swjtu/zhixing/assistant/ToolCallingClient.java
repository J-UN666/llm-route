package cn.swjtu.zhixing.assistant;

/**
 * 模型工具调用协议客户端；单测可注入可重放实现，生产实现见
 * {@link OpenAiToolCallingClient}。
 */
public interface ToolCallingClient {

    /**
     * 提交一轮对话（含历史消息与工具声明），返回模型文本或工具调用。
     *
     * @throws ToolCallingException 配置缺失、超时或上游响应异常
     */
    ToolCallingResponse complete(ToolCallingRequest request);

    default ToolCallingResponse completeWithKey(ToolCallingRequest request, String key) {
        throw new ToolCallingException(503, "MODEL_NOT_CONFIGURED", "当前模型服务不支持个人配置", false);
    }

    default void verifyPersonalKey(String key, String model) {
        throw new ToolCallingException(503, "MODEL_NOT_CONFIGURED", "当前模型服务不支持个人配置", false);
    }
}
