package cn.swjtu.zhixing.journey;

import cn.swjtu.zhixing.serviceconfig.PersonalServices;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 比较校园出发和城市出发的两个人到同一地点的行程。 */
@RestController
@RequestMapping("/api/decision-making")
class DecisionMakingController {
    private final DecisionMakingService decisionMaking;
    private final PersonalServices personalServices;

    DecisionMakingController(DecisionMakingService decisionMaking, PersonalServices personalServices) {
        this.decisionMaking = decisionMaking;
        this.personalServices = personalServices;
    }

    @PostMapping("/compare")
    DecisionMakingService.Reply compare(@RequestBody(required = false) DecisionMakingService.Request request,
                                        @RequestHeader(name = PersonalServices.OWNER_HEADER, required = false) String token,
                                        HttpServletRequest httpRequest) {
        // 使用和普通行程相同的默认或个人高德配置。
        return decisionMaking.compare(request, personalServices.forRequest(token, httpRequest));
    }
}
