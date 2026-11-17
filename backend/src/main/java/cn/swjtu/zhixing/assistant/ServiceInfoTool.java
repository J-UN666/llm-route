package cn.swjtu.zhixing.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 最小 Java 工具：读取真实服务配置与运行时信息。
 *
 * <p>服务名来自 {@code spring.application.name}，版本来自 W1-02 打包生成的
 * {@link BuildProperties}，Java 版本来自运行时系统属性，业务时区固定为
 * {@code Asia/Shanghai}。工具参数必须是空对象，调用由
 * {@link ToolCheckService} 按允许清单分发。</p>
 */
@Component
public class ServiceInfoTool {

    public static final String NAME = "get_service_info";
    public static final String DESCRIPTION =
            "读取后端服务名称、构建版本、Java 运行时版本与业务时区。该工具不接受参数，调用时传空对象。";
    public static final Map<String, Object> PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(),
            "additionalProperties", false);
    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, DESCRIPTION, PARAMETERS);

    /** 项目统一业务时区；与地点、接驳、行程接口一致。 */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final Environment environment;
    private final BuildProperties buildProperties;

    public ServiceInfoTool(Environment environment, BuildProperties buildProperties) {
        this.environment = environment;
        this.buildProperties = buildProperties;
    }

    /**
     * 执行工具：校验参数为空对象后返回真实服务信息。
     *
     * @throws IllegalArgumentException 参数不是空 JSON 对象时抛出，由服务层映射为
     *                                  INVALID_TOOL_ARGUMENTS
     */
    public ServiceInfo execute(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("参数必须是 JSON 对象");
        }
        if (arguments.size() > 0) {
            throw new IllegalArgumentException("该工具不接受参数，需要传空对象 {}");
        }
        String applicationName = environment.getProperty("spring.application.name", "llm-route-backend");
        String applicationVersion = buildProperties == null ? null : buildProperties.getVersion();
        if (applicationVersion == null || applicationVersion.isBlank()) {
            applicationVersion = "unknown";
        }
        return new ServiceInfo(
                applicationName,
                applicationVersion,
                System.getProperty("java.version"),
                BUSINESS_ZONE.getId());
    }
}
