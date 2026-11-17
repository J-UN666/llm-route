package cn.swjtu.zhixing.assistant;

/** assistant 接口对外错误码，保持稳定以便前端与交接文档引用。 */
final class AssistantErrorCodes {

    static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    static final String MODEL_NOT_CONFIGURED = "MODEL_NOT_CONFIGURED";
    static final String MODEL_UPSTREAM_ERROR = "MODEL_UPSTREAM_ERROR";
    static final String MODEL_TIMEOUT = "MODEL_TIMEOUT";
    static final String INVALID_TOOL_NAME = "INVALID_TOOL_NAME";
    static final String INVALID_TOOL_ARGUMENTS = "INVALID_TOOL_ARGUMENTS";
    static final String MODEL_TOOL_ROUND_LIMIT_EXCEEDED = "MODEL_TOOL_ROUND_LIMIT_EXCEEDED";
    static final String SERVER_ERROR = "SERVER_ERROR";

    private AssistantErrorCodes() {
    }
}
