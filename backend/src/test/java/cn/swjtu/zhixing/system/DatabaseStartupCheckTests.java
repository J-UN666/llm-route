package cn.swjtu.zhixing.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;
import javax.sql.DataSource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * 启动数据库检查验证：成功时输出当前库与数据库版本，失败时告警但不抛出，
 * 两种路径的日志都不包含账号、口令或连接串。
 */
class DatabaseStartupCheckTests {

    @Test
    void logsDatabaseNameAndVersionWithoutCredentials() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(1);
        when(connection.getCatalog()).thenReturn("llmroute");
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductVersion()).thenReturn("8.4.13");

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            new DatabaseStartupCheck(dataSource).run(new DefaultApplicationArguments(new String[0]));
        } finally {
            detachAppender(appender);
        }

        String logs = logsOf(appender);
        assertThat(logs).contains("SELECT 1 成功").contains("当前库=llmroute").contains("数据库版本=8.4.13");
        assertThat(logs).doesNotContain("password").doesNotContain("13306").doesNotContain("127.0.0.1");
    }

    @Test
    void doesNotThrowWhenDatabaseIsUnavailable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException(
                "Access denied for user 'app_user'@'127.0.0.1' (using password: YES) at jdbc:mysql://127.0.0.1:13306/llmroute"));

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            new DatabaseStartupCheck(dataSource).run(new DefaultApplicationArguments(new String[0]));
        } finally {
            detachAppender(appender);
        }

        String logs = logsOf(appender);
        assertThat(logs).contains("数据库启动检查失败");
        assertThat(logs).doesNotContain("app_user").doesNotContain("13306").doesNotContain("YES");
    }

    @Test
    void sanitizesCredentialsAndConnectionDetails() {
        String sanitized = DatabaseStartupCheck.sanitize(
                "Access denied for user 'app_user'@'127.0.0.1' (using password: YES) "
                        + "url=jdbc:mysql://127.0.0.1:13306/llmroute?password=s3cret");

        assertThat(sanitized)
                .doesNotContain("app_user")
                .doesNotContain("127.0.0.1")
                .doesNotContain("13306")
                .doesNotContain("s3cret")
                .contains("Access denied");
        assertThat(DatabaseStartupCheck.sanitize(null)).isEqualTo("<no message>");
    }

    @Test
    void sanitizesUppercaseJdbcSchemeAndAccountFields() {
        String sanitized = DatabaseStartupCheck.sanitize(
                "JDBC:mysql://127.0.0.1:13306/llmroute?user=app_user&password=s3cret");

        assertThat(sanitized)
                .doesNotContain("app_user")
                .doesNotContain("s3cret")
                .doesNotContain("127.0.0.1")
                .doesNotContain("13306")
                .contains("jdbc:***");
        assertThat(DatabaseStartupCheck.sanitize("username: app_user")).isEqualTo("username: ***");
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(DatabaseStartupCheck.class)).addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(DatabaseStartupCheck.class)).detachAppender(appender);
    }

    private static String logsOf(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }
}
