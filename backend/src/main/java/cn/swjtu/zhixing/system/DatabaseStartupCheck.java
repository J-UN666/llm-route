package cn.swjtu.zhixing.system;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时通过实际数据源执行 {@code SELECT 1}，确认当前库与数据库版本。
 *
 * <p>数据库不可用时只记录告警，不阻断应用启动：存活检查不依赖数据库，
 * 数据库是否就绪由 readiness 组单独反映。日志只输出库名与数据库版本，
 * 账号、口令、连接串等细节在输出前脱敏。</p>
 */
@Component
public class DatabaseStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseStartupCheck.class);

    /** JDBC 连接串（含可选口令参数），scheme 大小写不敏感。 */
    private static final Pattern JDBC_URL = Pattern.compile("(?i)jdbc:[a-z0-9]+://\\S+");

    /** {@code 'user'@'host'} 形式的账号与来源主机。 */
    private static final Pattern ACCOUNT_AT_HOST = Pattern.compile("'[^'@]*'@'[^']*'");

    /** 告警文本中的账号字段（连接参数形式，如 user=xxx、username: xxx）。 */
    private static final Pattern USERNAME_FIELD =
            Pattern.compile("(?i)\\b(user|username)(\\s*[=:]\\s*)([^,\\s)]+)");

    /** 告警文本中的口令字段。 */
    private static final Pattern PASSWORD_FIELD =
            Pattern.compile("(?i)(password|pwd)(\\s*[=:]\\s*)([^,\\s)]+)");

    /** {@code host:port} 形式的连接细节。 */
    private static final Pattern HOST_PORT = Pattern.compile("\\b(?:[a-zA-Z0-9_-]+\\.)*[a-zA-Z0-9_-]+:\\d{2,5}\\b");

    private final DataSource dataSource;

    public DatabaseStartupCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            if (!resultSet.next()) {
                log.warn("数据库启动检查未返回结果，应用继续启动");
                return;
            }
            resultSet.getInt(1);
            log.info("数据库启动检查通过: SELECT 1 成功, 当前库={}, 数据库版本={}, 耗时={}ms",
                    valueOrUnknown(connection.getCatalog()),
                    valueOrUnknown(connection.getMetaData().getDatabaseProductVersion()),
                    elapsedMillis(startedAt));
        } catch (SQLException | RuntimeException ex) {
            log.warn("数据库启动检查失败（应用继续启动，数据库就绪状态为 DOWN）: {}, 耗时={}ms",
                    sanitize(ex.getMessage()), elapsedMillis(startedAt));
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value;
    }

    /**
     * 脱敏异常文本，避免凭证与连接细节进入日志。
     *
     * <p>只保留可诊断的错误语义，去掉 JDBC 连接串、账号@主机、host:port 与口令字段。</p>
     */
    static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "<no message>";
        }
        String sanitized = JDBC_URL.matcher(message).replaceAll("jdbc:***");
        sanitized = ACCOUNT_AT_HOST.matcher(sanitized).replaceAll("'***'@'***'");
        sanitized = USERNAME_FIELD.matcher(sanitized).replaceAll("$1$2***");
        sanitized = PASSWORD_FIELD.matcher(sanitized).replaceAll("$1$2***");
        sanitized = HOST_PORT.matcher(sanitized).replaceAll("***:****");
        return sanitized;
    }
}
