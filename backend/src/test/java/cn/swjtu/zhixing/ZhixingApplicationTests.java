package cn.swjtu.zhixing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * 启动验证：即使数据库不可用应用也能启动（存活检查只反映进程状态），
 * 并确认打包产物带有真实工程版本的 BuildProperties（供 W1-13 读取）。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "DB_URL=jdbc:mysql://127.0.0.1:1/llmroute")
class ZhixingApplicationTests {

    @Autowired
    private Environment environment;

    @Autowired
    private BuildProperties buildProperties;

    @Test
    void applicationStartsWithExpectedName() {
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("llm-route-backend");
    }

    @Test
    void buildInfoExposesRealProjectMetadata() {
        assertThat(buildProperties.getGroup()).isEqualTo("cn.swjtu.zhixing");
        assertThat(buildProperties.getArtifact()).isEqualTo("llm-route-backend");
        assertThat(buildProperties.getName()).isEqualTo("llm-route-backend");
        assertThat(buildProperties.getVersion()).isEqualTo("0.1.0-SNAPSHOT");
    }
}
