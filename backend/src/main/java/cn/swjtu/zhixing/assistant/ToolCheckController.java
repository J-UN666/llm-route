package cn.swjtu.zhixing.assistant;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型工具调用自检 HTTP 边界（W1-13）。
 *
 * <p>POST {@code /api/assistant/tool-check}：模型发起一次 get_service_info 调用，
 * 应用执行真实 Java 方法并把结果回传模型，返回最终回答。模型配置缺失 503，
 * 工具名/参数非法 422，模型超时 504，其他上游/协议异常 502；错误体由
 * {@link AssistantApiExceptionHandler} 输出 requestId/code/message/retryable。
 * 本接口与普通行程查询的失败处理完全独立。</p>
 */
@RestController
@RequestMapping("/api/assistant")
public class ToolCheckController {

    private final ToolCheckService toolCheckService;

    public ToolCheckController(ToolCheckService toolCheckService) {
        this.toolCheckService = toolCheckService;
    }

    @PostMapping("/tool-check")
    public ToolCheckResponse check(@RequestBody(required = false) ToolCheckRequest request) {
        return toolCheckService.check(request);
    }
}
