package cn.swjtu.zhixing.campus;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 校园接驳查询接口（W1-05）。
 *
 * <p>GET /api/campus/connectors?originId=...&departureTime=2026-11-15T09:00:00%2B08:00；
 * 返回 {originId, dataVersion, connectors:[CampusConnector]}。未知地点 404 PLACE_NOT_FOUND，
 * 非校园起点或当前时刻没有审核连接 422 NO_APPROVED_CONNECTOR（错误体带原因）。</p>
 */
@RestController
@RequestMapping("/api/campus/connectors")
public class CampusConnectorController {

    private final CampusConnectorService connectorService;

    public CampusConnectorController(CampusConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    @GetMapping
    public CampusConnectorsResponse getConnectors(
            @RequestParam(name = "originId", required = false) String originId,
            @RequestParam(name = "departureTime", required = false) String departureTime,
            @RequestParam(name = "atExit", defaultValue = "false") boolean atExit) {
        if (originId == null || originId.isBlank()) {
            throw new InvalidOriginIdException("缺少 originId 查询参数，示例：originId=osm_way_1098786977");
        }
        OffsetDateTime departure = parseDepartureTime(departureTime);
        CampusConnectorService.Resolution resolution = atExit ? connectorService.resolveAtExit(originId.trim(), departure)
                : connectorService.resolve(originId.trim(), departure);
        if (!"CAMPUS".equals(resolution.originKind())) {
            throw new NoApprovedConnectorException(
                    "地点 " + resolution.originId() + " 不是校园起点（kind=" + resolution.originKind()
                            + "），不提供校园步行接驳。",
                    resolution.originId(), resolution.originKind(), resolution.dataVersion(), resolution.unavailable());
        }
        if (resolution.available().isEmpty()) {
            throw new NoApprovedConnectorException(
                    "地点 " + resolution.originId() + " 在 " + departure
                            + "（Asia/Shanghai）没有审核可用的校门接驳；原因见 unavailable。",
                    resolution.originId(), resolution.originKind(), resolution.dataVersion(), resolution.unavailable());
        }
        return new CampusConnectorsResponse(resolution.originId(), resolution.dataVersion(), resolution.available());
    }

    /**
     * 解析带时区的 ISO-8601 时间。查询串里的 {@code +} 必须编码为 {@code %2B}，否则会被解码成空格。
     */
    private static OffsetDateTime parseDepartureTime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidDepartureTimeException(
                    "缺少 departureTime 查询参数，格式示例：2026-11-15T09:00:00%2B08:00（+ 号需编码为 %2B）");
        }
        String value = raw.trim();
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw new InvalidDepartureTimeException(
                    "departureTime 不是合法的 ISO-8601 带时区时间：" + value
                            + "；格式示例：2026-11-15T09:00:00%2B08:00（+ 号需编码为 %2B）");
        }
    }
}
