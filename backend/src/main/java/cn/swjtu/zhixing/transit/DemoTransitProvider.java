package cn.swjtu.zhixing.transit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deterministic city samples for the explicitly activated offline demo. */
@Component
@Profile("demo")
class DemoTransitProvider implements TransitProvider {
    private static final String SOURCE = "示例路线";
    private static final String EAST_EXIT = "osm_node_13469658219";

    private record Sample(String name, String type, int duration, int walk, BigDecimal fare) {}

    private static final Map<String, List<Sample>> SAMPLES = Map.of(
            "osm_way_1395271422", List.of(
                    new Sample("示例地铁 A", "METRO", 2400, 650, BigDecimal.valueOf(5)),
                    new Sample("示例公交 B", "BUS", 3300, 350, BigDecimal.valueOf(2))),
            "osm_way_243328355", List.of(
                    new Sample("示例地铁 C", "METRO", 2580, 720, BigDecimal.valueOf(5)),
                    new Sample("示例公交 D", "BUS", 3540, 410, null)));

    @Override
    public TransitSearchResult search(TransitSearchRequest request) {
        OffsetDateTime departure = request.departureTime();
        List<Sample> samples = EAST_EXIT.equals(request.originPoint().pointId())
                ? SAMPLES.getOrDefault(request.destinationPoint().pointId(), List.of()) : List.of();
        List<TransitPlan> plans = samples.stream().map(DemoTransitProvider::plan).toList();
        return new TransitSearchResult("DEMO", OffsetDateTime.now(), departure, departure, 0,
                plans.isEmpty() ? TransitStatus.NO_ROUTE : TransitStatus.OK, plans);
    }

    private static TransitPlan plan(Sample sample) {
        int walkSeconds = sample.walk();
        int rideSeconds = sample.duration() - walkSeconds;
        TransitLine line = new TransitLine(sample.name(), sample.name(), sample.type(), null,
                "示例上车点", "示例下车点", rideSeconds, 12, null, SOURCE);
        return new TransitPlan(sample.duration(), sample.walk(), 0, sample.fare(), List.of(
                new TransitSegment("WALK", walkSeconds, sample.walk(), null, "示例上车点", List.of(), null, SOURCE),
                new TransitSegment(sample.type(), rideSeconds, null, "示例上车点", "示例下车点",
                        List.of(line), null, SOURCE)), SOURCE);
    }
}
