package cn.swjtu.zhixing.assistant;

/**
 * get_service_info 工具的实际 Java 返回值；全部字段来自真实服务配置与运行时，
 * 不使用模型生成的服务信息。
 */
public record ServiceInfo(
        String applicationName,
        String applicationVersion,
        String javaVersion,
        String businessTimeZone) {
}
