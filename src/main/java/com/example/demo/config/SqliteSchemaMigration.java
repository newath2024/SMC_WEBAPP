package com.example.demo.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class SqliteSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SqliteSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureUsersPlanTypeColumn();
    }

    private void ensureUsersPlanTypeColumn() {
        if (!tableExists("users")) {
            return;
        }

        if (columnExists("users", "plan_type")) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE users ADD COLUMN plan_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD'");
        jdbcTemplate.update("UPDATE users SET plan_type = 'ADMIN' WHERE upper(coalesce(role, '')) = 'ADMIN'");
        jdbcTemplate.update("UPDATE users SET plan_type = 'STANDARD' WHERE plan_type IS NULL OR trim(plan_type) = ''");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        List<String> columns = jdbcTemplate.query(
                "PRAGMA table_info(" + tableName + ")",
                (rs, rowNum) -> rs.getString("name")
        );

        return columns.stream()
                .filter(name -> name != null)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.equals(columnName.toLowerCase(Locale.ROOT)));
    }
}
