package cn.swjtu.zhixing.journey;

/**
 * 普通行程接口错误体；字段与 W1-08 任务卡、W1-07 前端一致。
 *
 * <p>{@code code} 为可机读的错误码，{@code message} 不包含上游 key、完整 URL 或原始响应。</p>
 */
public record JourneyError(
        String requestId,
        String code,
        String message,
        boolean retryable) {
}
