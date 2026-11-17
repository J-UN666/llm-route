package cn.swjtu.zhixing.assistant;

/**
 * POST /api/assistant/tool-check 请求体。
 *
 * <p>{@code message} 为一句测试消息，例如任务卡约定的
 * {@code 请调用工具查看当前服务信息}；空白消息返回 INVALID_PARAMETER。</p>
 */
public record ToolCheckRequest(String message) {
}
