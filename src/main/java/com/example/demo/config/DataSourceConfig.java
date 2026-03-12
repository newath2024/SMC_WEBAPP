package com.example.demo.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

@Configuration
public class DataSourceConfig {

    @Bean
    DataSource dataSource(Environment environment) {
        String configuredUrl = firstNonBlank(
                environment.getProperty("SPRING_DATASOURCE_URL"),
                environment.getProperty("DATABASE_URL"));

        if (!StringUtils.hasText(configuredUrl)) {
            throw new IllegalStateException("Missing SPRING_DATASOURCE_URL or DATABASE_URL");
        }

        DatabaseSettings settings = resolveSettings(environment, configuredUrl);

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
        String jdbcUrl = toJdbcUrl(rawUrl);
        String username = firstNonBlank(environment.getProperty("SPRING_DATASOURCE_USERNAME"), extractUsername(rawUrl));
        String password = firstNonBlank(environment.getProperty("SPRING_DATASOURCE_PASSWORD"), extractPassword(rawUrl));
        return new DatabaseSettings(jdbcUrl, username, password);
    }

    private String toJdbcUrl(String rawUrl) {
        if (rawUrl.startsWith("jdbc:postgresql://")) {
            return rawUrl;
        }
        if (rawUrl.startsWith("postgres://") || rawUrl.startsWith("postgresql://")) {
            URI uri = URI.create(rawUrl);
            String host = Objects.requireNonNull(uri.getHost(), "Database host is missing");
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = uri.getPath();
            String query = uri.getQuery();
            return "jdbc:postgresql://" + host + ":" + port + path + (query == null ? "" : "?" + query);
        }
        throw new IllegalStateException("Unsupported database URL: " + rawUrl);
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
