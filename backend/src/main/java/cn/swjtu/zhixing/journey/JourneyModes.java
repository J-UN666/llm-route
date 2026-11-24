package cn.swjtu.zhixing.journey;

import java.util.List;

/** 多方式查询的请求和响应。校门留空时由系统逐类选择。 */
final class JourneyModes {
    private JourneyModes() {}

    record Request(String originId, String destinationId, String departureTime, String gateId) {}

    enum Mode {
        TAXI("打车"), METRO("地铁"), WALK("步行");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    enum Status { SUCCEEDED, PARTIAL, NO_ROUTE }

    record Plan(Mode mode, JourneyPlan route) {}

    record Reply(String requestId, Status status, String provider, List<Plan> plans, List<String> warnings) {}
}
