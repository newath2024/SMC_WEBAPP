package com.tradejournal.mistake.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

@Service
public class MistakeTagOwnershipMigrationService {

    private static final Logger log = LoggerFactory.getLogger(MistakeTagOwnershipMigrationService.class);

    private final DataSource dataSource;

    public MistakeTagOwnershipMigrationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateMistakeTagOwnershipColumnIfNeeded() {
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection, "mistake_tags") || columnExists(connection, "mistake_tags", "user_id")) {
                return;
            }

            String productName = connection.getMetaData().getDatabaseProductName();
            String normalizedProduct = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
            if (normalizedProduct.contains("sqlite") || normalizedProduct.contains("postgresql")) {
                addUserIdColumn(connection);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not update mistake tag schema for per-user ownership.", ex);
        }
    }

    private void addUserIdColumn(Connection connection) throws SQLException {
        log.info("Adding user_id column to mistake_tags for per-user custom mistake tags.");
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table mistake_tags add column user_id varchar(36)");
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, null, tableName, null)) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = connection.getMetaData().getTables(null, null, tableName.toUpperCase(Locale.ROOT), null)) {
            return resultSet.next();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        } catch (SQLException ignored) {
        }
        return false;
    }
}
