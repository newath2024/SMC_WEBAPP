package com.example.demo.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Configuration
@Profile("!local")
public class DataSourceConfig {

    @Bean
    DataSource dataSource(Environment environment) {
        String configuredUrl = firstNonBlank(
                environment.getProperty("SPRING_DATASOURCE_URL"),
                environment.getProperty("DATABASE_URL"));
        DatabaseSettings settings = StringUtils.hasText(configuredUrl)
                ? resolveSettings(environment, configuredUrl)
                : resolveSettingsFromParts(environment);

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 5));
        config.setMinimumIdle(environment.getProperty("spring.datasource.hikari.minimum-idle", Integer.class, 1));
        config.setConnectionTimeout(environment.getProperty("spring.datasource.hikari.connection-timeout", Long.class, 30000L));
        return new HikariDataSource(config);
    }

    private DatabaseSettings resolveSettings(Environment environment, String rawUrl) {
        String jdbcUrl = toJdbcUrl(rawUrl, environment.getProperty("DB_SSL_MODE"));
        String username = firstNonBlank(environment.getProperty("SPRING_DATASOURCE_USERNAME"), extractUsername(rawUrl));
        String password = firstNonBlank(environment.getProperty("SPRING_DATASOURCE_PASSWORD"), extractPassword(rawUrl));
        return new DatabaseSettings(jdbcUrl, username, password);
    }

    private DatabaseSettings resolveSettingsFromParts(Environment environment) {
        String host = environment.getProperty("SPRING_DATASOURCE_HOST");
        String port = environment.getProperty("SPRING_DATASOURCE_PORT", "5432");
        String database = environment.getProperty("SPRING_DATASOURCE_DATABASE");
        String username = environment.getProperty("SPRING_DATASOURCE_USERNAME");
        String password = environment.getProperty("SPRING_DATASOURCE_PASSWORD");

        if (!StringUtils.hasText(host) || !StringUtils.hasText(database)) {
            throw new IllegalStateException(
                    "Missing database config. Set SPRING_DATASOURCE_URL, DATABASE_URL, or SPRING_DATASOURCE_HOST/PORT/DATABASE");
        }

        String sslMode = environment.getProperty("DB_SSL_MODE");
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database
                + (StringUtils.hasText(sslMode) ? "?sslmode=" + sslMode : "");
        return new DatabaseSettings(jdbcUrl, username, password);
    }

    private String toJdbcUrl(String rawUrl, String sslMode) {
        if (rawUrl.startsWith("jdbc:postgresql://")) {
            return appendSslMode(rawUrl, sslMode);
        }
        if (rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://")) {
            URI uri = URI.create(rawUrl);
            String host = Objects.requireNonNull(uri.getHost(), "Database host is missing");
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = uri.getPath();
            String query = uri.getQuery();
            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path + (query == null ? "" : "?" + query);
            return appendSslMode(jdbcUrl, sslMode);
        }
        throw new IllegalStateException("Unsupported database URL: " + rawUrl);
    }

    private String appendSslMode(String jdbcUrl, String sslMode) {
        if (!StringUtils.hasText(sslMode) || jdbcUrl.contains("sslmode=")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "sslmode=" + sslMode;
    }

    private String extractUsername(String rawUrl) {
        String userInfo = extractUserInfo(rawUrl);
        if (!StringUtils.hasText(userInfo) || !userInfo.contains(":")) {
            return null;
        }
        return userInfo.substring(0, userInfo.indexOf(':'));
    }

    private String extractPassword(String rawUrl) {
        String userInfo = extractUserInfo(rawUrl);
        if (!StringUtils.hasText(userInfo) || !userInfo.contains(":")) {
            return null;
        }
        return userInfo.substring(userInfo.indexOf(':') + 1);
    }

    private String extractUserInfo(String rawUrl) {
        if (!(rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://"))) {
            return null;
        }
        return URI.create(rawUrl).getUserInfo();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private record DatabaseSettings(String jdbcUrl, String username, String password) {
    }
}
