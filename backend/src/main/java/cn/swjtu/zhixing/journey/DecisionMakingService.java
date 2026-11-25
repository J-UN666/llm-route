package cn.swjtu.zhixing.journey;

import cn.swjtu.zhixing.serviceconfig.PersonalServices.Credentials;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class DecisionMakingService {
    record Request(String campusOriginId, String cityOriginId, String destinationId, String departureTime) {}

    enum Status { SUCCEEDED, PARTIAL, NO_ROUTE }

    record Reply(String requestId, Status status, String provider, JourneyPlan campusPlan, JourneyPlan cityPlan,
                 Integer durationDifferenceSeconds, BigDecimal distanceDifferenceMeters, List<String> warnings) {}

    private final JourneyService journeys;

    DecisionMakingService(JourneyService journeys) {
        this.journeys = journeys;
    }

    Reply compare(Request request, Credentials credentials) {
        String requestId = JourneyIds.requestId();
        if (request == null || isBlank(request.campusOriginId()) || isBlank(request.cityOriginId())
                || isBlank(request.destinationId())) {
            throw new JourneyApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "请提供校园起点、城市起点和共同终点", false, requestId);
        }
        OffsetDateTime departure = JourneyService.parseDeparture(request.departureTime(), requestId);
        var routes = journeys.planPair(request.campusOriginId().trim(), request.cityOriginId().trim(),
                request.destinationId().trim(), departure, credentials, requestId);
        var campus = routes.campus();
        var city = routes.city();

        // 推荐模式按耗时排列，每人取各自最快的一条，再比较两人的完整行程。
        JourneyPlan campusPlan = campus.plans().isEmpty() ? null : campus.plans().getFirst();
        JourneyPlan cityPlan = city.plans().isEmpty() ? null : city.plans().getFirst();
        Status status;
        Integer durationDifference = null;
        BigDecimal distanceDifference = null;
        if (campusPlan != null && cityPlan != null) {
            status = Status.SUCCEEDED;
            if (campusPlan.totalDurationSeconds() != null && cityPlan.totalDurationSeconds() != null) {
                durationDifference = Math.abs(campusPlan.totalDurationSeconds() - cityPlan.totalDurationSeconds());
            }
            if (campusPlan.totalDistanceMeters() != null && cityPlan.totalDistanceMeters() != null) {
                distanceDifference = campusPlan.totalDistanceMeters().subtract(cityPlan.totalDistanceMeters()).abs();
            }
        } else {
            status = campusPlan == null && cityPlan == null ? Status.NO_ROUTE : Status.PARTIAL;
        }
        var warnings = new LinkedHashSet<>(campus.warnings());
        warnings.addAll(city.warnings());
        return new Reply(requestId, status, campus.provider() != null ? campus.provider() : city.provider(),
                campusPlan, cityPlan, durationDifference, distanceDifference, List.copyOf(warnings));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
