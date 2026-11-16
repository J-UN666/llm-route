package cn.swjtu.zhixing.journey;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 普通行程查询 HTTP 边界。
 *
 * <p>HTTP 映射：成功 200 + {@code SUCCEEDED}；有效查询无候选 200 + {@code NO_ROUTE} + 空 plans；
 * 参数错误 400；未知地点 404；地点/接驳/坐标/时间能力不可用 422；上游失败 502；上游超时 504。
 * 错误体统一由 {@link JourneyApiExceptionHandler} 输出 requestId/code/message/retryable。</p>
 */
@RestController
@RequestMapping("/api/journeys")
public class JourneyController {

    private final JourneyService journeyService;

    public JourneyController(JourneyService journeyService) {
        this.journeyService = journeyService;
    }

    @PostMapping
    public JourneyResponse plan(@RequestBody(required = false) JourneyRequest request) {
        return journeyService.plan(request);
    }
}
