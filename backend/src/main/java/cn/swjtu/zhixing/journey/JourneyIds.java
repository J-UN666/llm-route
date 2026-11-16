package cn.swjtu.zhixing.journey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 请求与方案标识生成。方案 ID 由请求标识与实际路线身份确定性哈希得到，不依赖提供方返回顺序。 */
final class JourneyIds {

    private JourneyIds() {
    }

    static String requestId() {
        return "jr-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /**
     * 方案 ID：由起点、终点、请求出发时刻、实际校门与路线身份（出校点 + 乘车序列）哈希。
     * 不使用提供方返回序号，因此同一条路线即使被上游交换顺序，planId 也不变；同一查询
     * 重复执行保持稳定。
     */
    static String planId(String originId,
                         String destinationId,
                         String departureTime,
                         String gateId,
                         String routeIdentity) {
        String canonical = String.join("|",
                nullToEmpty(originId),
                nullToEmpty(destinationId),
                nullToEmpty(departureTime),
                nullToEmpty(gateId),
                nullToEmpty(routeIdentity));
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
