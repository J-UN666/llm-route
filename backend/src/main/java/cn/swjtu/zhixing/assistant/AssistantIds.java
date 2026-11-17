package cn.swjtu.zhixing.assistant;

import java.util.UUID;

/** 模型工具调用自检接口的请求标识。 */
final class AssistantIds {

    private AssistantIds() {
    }

    static String requestId() {
        return "tc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
