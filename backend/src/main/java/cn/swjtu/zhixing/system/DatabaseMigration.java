package cn.swjtu.zhixing.system;

import org.flywaydb.core.Flyway;

/** Allows the same published JAR to apply its migrations with the DDL account once. */
public final class DatabaseMigration {
    private DatabaseMigration() {}

    public static void run() {
        Flyway.configure()
                .dataSource(required("MIGRATION_DB_URL"), required("MIGRATION_DB_USER"),
                        required("MIGRATION_DB_PASSWORD"))
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load().migrate();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing migration variable: " + name);
        }
        return value;
    }
}
