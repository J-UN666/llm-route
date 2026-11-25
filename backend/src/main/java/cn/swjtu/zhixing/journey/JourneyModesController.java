package cn.swjtu.zhixing.journey;

import cn.swjtu.zhixing.serviceconfig.PersonalServices;
import cn.swjtu.zhixing.transit.AmapRoadProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 一次返回打车、地铁、步行各自的最快方案。 */
@RestController
@Profile("!demo")
@RequestMapping("/api/journeys/by-mode")
class JourneyModesController {
    private final JourneyService journeys;
    private final AmapRoadProvider roadProvider;
    private final PersonalServices personalServices;

    JourneyModesController(JourneyService journeys, AmapRoadProvider roadProvider, PersonalServices personalServices) {
        this.journeys = journeys;
        this.roadProvider = roadProvider;
        this.personalServices = personalServices;
    }

    @PostMapping
    JourneyModes.Reply plan(@RequestBody(required = false) JourneyModes.Request request,
                            @RequestHeader(name = PersonalServices.OWNER_HEADER, required = false) String token,
                            HttpServletRequest httpRequest) {
        return journeys.planModes(request, personalServices.forRequest(token, httpRequest), roadProvider);
    }
}
