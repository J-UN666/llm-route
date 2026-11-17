package cn.swjtu.zhixing.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.env.MockEnvironment;

/** get_service_info 工具读取真实配置与运行时、并严格校验空参数。 */
class ServiceInfoToolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ServiceInfoTool tool() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "llm-route-backend");
        Properties properties = new Properties();
        properties.setProperty("group", "cn.swjtu.zhixing");
        properties.setProperty("artifact", "llm-route-backend");
        properties.setProperty("name", "llm-route-backend");
        properties.setProperty("version", "0.1.0-SNAPSHOT");
        return new ServiceInfoTool(environment, new BuildProperties(properties));
    }

    @Test
    void returnsRealServiceConfigurationAndRuntime() throws IOException {
        ServiceInfo info = tool().execute(objectMapper.readTree("{}"));

        assertThat(info.applicationName()).isEqualTo("llm-route-backend");
        assertThat(info.applicationVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(info.javaVersion()).isEqualTo(System.getProperty("java.version"));
        assertThat(info.businessTimeZone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void rejectsNonEmptyArguments() throws IOException {
        assertThatThrownBy(() -> tool().execute(objectMapper.readTree("{\"verbose\":true}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空对象");
    }

    @Test
    void rejectsNonObjectArguments() throws IOException {
        assertThatThrownBy(() -> tool().execute(objectMapper.readTree("[]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 对象");
        assertThatThrownBy(() -> tool().execute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declaresEmptyObjectJsonSchema() {
        ToolDefinition definition = ServiceInfoTool.DEFINITION;
        assertThat(definition.name()).isEqualTo("get_service_info");
        assertThat(definition.parameters()).containsEntry("type", "object");
        assertThat(definition.parameters()).containsEntry("additionalProperties", false);
        assertThat((java.util.Map<?, ?>) definition.parameters().get("properties")).isEmpty();
    }

    @Test
    void fallsBackToUnknownVersionWhenBuildInfoMissing() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "llm-route-backend");
        ServiceInfo info = new ServiceInfoTool(environment, null).execute(objectMapper.readTree("{}"));
        assertThat(info.applicationVersion()).isEqualTo("unknown");
    }
}
