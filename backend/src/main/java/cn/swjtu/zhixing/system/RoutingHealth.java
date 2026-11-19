package cn.swjtu.zhixing.system;

import cn.swjtu.zhixing.assistant.AssistantProperties;
import cn.swjtu.zhixing.campus.CampusConnectorService;
import cn.swjtu.zhixing.campus.PlaceService;
import cn.swjtu.zhixing.transit.AmapProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class RoutingHealth {
    @Bean
    HealthIndicator campus(PlaceService places, CampusConnectorService connectors) {
        return () -> {
            try {
                places.dataVersion();
                connectors.dataVersion();
                return Health.up().build();
            } catch (RuntimeException ex) {
                return Health.down().build();
            }
        };
    }

    @Bean
    HealthIndicator transitConfiguration(AmapProperties settings, Environment environment) {
        return () -> environment.acceptsProfiles(Profiles.of("demo")) || !settings.getWebServiceKey().isBlank()
                ? Health.up().build() : Health.down().build();
    }

    @Bean
    HealthIndicator modelConfiguration(AssistantProperties settings) {
        return () -> settings.isConfigured() ? Health.up().build() : Health.down().build();
    }

    @Bean
    HealthIndicator conversationSchema(JdbcTemplate jdbc) {
        return () -> {
            try {
                jdbc.execute("SELECT 1 FROM conversation_turn LIMIT 0");
                return Health.up().build();
            } catch (RuntimeException ex) {
                return Health.down().build();
            }
        };
    }
}
