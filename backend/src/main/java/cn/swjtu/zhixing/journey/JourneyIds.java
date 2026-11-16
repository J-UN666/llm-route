package cn.swjtu.zhixing.journey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 请求与方案标识生成。方案 ID 由输入与城市段结果确定性哈希得到，同一查询重复执行保持稳定。 */
final class JourneyIds {

    private JourneyIds() {
    }

    static String requestId() {
        return "jr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    static String planId(String originId,
                         String destinationId,
                         String departureTime,
                         String gateId,
                         int providerPlanIndex,
                         String providerDepartureTime,
                         Integer durationSeconds,
                         Integer walkingMeters,
                         Integer transferCount,
                         String costYuan) {
        String canonical = String.join("|",
                nullToEmpty(originId),
                nullToEmpty(destinationId),
                nullToEmpty(departureTime),
                nullToEmpty(gateId),
                Integer.toString(providerPlanIndex),
                nullToEmpty(providerDepartureTime),
                nullToEmpty(durationSeconds),
                nullToEmpty(walkingMeters),
                nullToEmpty(transferCount),
                nullToEmpty(costYuan));
        return "plan_" + sha256(canonical).substring(0, 16);
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }
}
