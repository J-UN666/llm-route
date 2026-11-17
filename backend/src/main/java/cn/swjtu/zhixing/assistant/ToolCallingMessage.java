package cn.swjtu.zhixing.assistant;

import java.util.List;

/**
 * 发送给模型的一条 Chat Completions 消息。
 *
 * <p>角色：{@code system}/{@code user}（content）、{@code assistant}（可带 toolCalls）、
 * {@code tool}（content 为工具结果 JSON，并携带 toolCallId）。</p>
 */
public record ToolCallingMessage(
        String role,
        String content,
        String toolCallId,
        List<ModelToolCall> toolCalls) {
}
