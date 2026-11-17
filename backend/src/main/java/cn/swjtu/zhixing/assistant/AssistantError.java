package cn.swjtu.zhixing.assistant;

/**
 * 模型工具调用自检接口错误体；字段与既有接口一致，便于前端/工具统一处理。
 *
 * <p>{@code message} 不包含模型密钥、完整上游 URL 或原始响应。</p>
 */
public record AssistantError(
        String requestId,
        String code,
        String message,
        boolean retryable) {
}
