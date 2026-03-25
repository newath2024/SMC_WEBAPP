package com.tradejournal.trade.service;

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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class TradeTimeSchemaMigrationService {

    private static final Logger log = LoggerFactory.getLogger(TradeTimeSchemaMigrationService.class);

    private final DataSource dataSource;

    public TradeTimeSchemaMigrationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateTradeTimeColumnsIfNeeded() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            String normalizedProduct = productName == null ? "" : productName.toLowerCase(Locale.ROOT);

            if (normalizedProduct.contains("sqlite")) {
                migrateSqlite(connection);
                return;
            }

            if (normalizedProduct.contains("postgresql")) {
                migratePostgres(connection);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not update trade time schema to allow nullable screenshot timestamps.", ex);
        }
    }

    private void migrateSqlite(Connection connection) throws SQLException {
        if (!tableExists(connection, "trades")) {
            return;
        }

        Map<String, Boolean> notNullByColumn = sqliteNotNullColumns(connection, "trades");
        boolean entryTimeNotNull = Boolean.TRUE.equals(notNullByColumn.get("entry_time"));
        boolean exitTimeNotNull = Boolean.TRUE.equals(notNullByColumn.get("exit_time"));

        if (!entryTimeNotNull && !exitTimeNotNull) {
            return;
        }

        log.info("Migrating SQLite trades table to allow nullable entry_time/exit_time.");
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("BEGIN TRANSACTION");
            statement.execute("""
                    create table trades__migration (
                        id varchar(36) not null primary key,
                        trade_date timestamp,
                        entry_time timestamp,
                        exit_time timestamp,
                        account_label varchar(255) not null,
                        user_id varchar(36) not null,
                        setup_id varchar(36),
                        symbol varchar(255) not null,
                        direction varchar(255) not null,
                        htf varchar(255) not null,
                        ltf varchar(255) not null,
                        entry_price float not null,
                        stop_loss float not null,
                        initial_stop_loss float,
                        initial_stop_loss_confirmed boolean,
                        take_profit float not null,
                        exit_price float not null,
                        position_size float not null,
                        mt5_position_id varchar(64),
                        result varchar(255),
                        pnl float not null,
                        r_multiple float not null,
                        r_multiple_source varchar(32),
                        session varchar(255),
                        estimated_holding_minutes integer,
                        estimated_ltf_candles_held integer,
                        session_guess varchar(32),
                        session_confidence varchar(16),
                        note text,
                        created_at timestamp,
                        updated_at timestamp,
                        foreign key (user_id) references users(id),
                        foreign key (setup_id) references setups(id)
                    )
                    """);
            statement.execute("""
                    insert into trades__migration (
                        id, trade_date, entry_time, exit_time, account_label, user_id, setup_id, symbol, direction,
                        htf, ltf, entry_price, stop_loss, initial_stop_loss, initial_stop_loss_confirmed,
                        take_profit, exit_price, position_size, mt5_position_id, result, pnl, r_multiple,
                        r_multiple_source, session, estimated_holding_minutes, estimated_ltf_candles_held,
                        session_guess, session_confidence, note, created_at, updated_at
                    )
                    select
                        id, trade_date, entry_time, exit_time, account_label, user_id, setup_id, symbol, direction,
                        htf, ltf, entry_price, stop_loss, initial_stop_loss, initial_stop_loss_confirmed,
                        take_profit, exit_price, position_size, mt5_position_id, result, pnl, r_multiple,
                        r_multiple_source, session, estimated_holding_minutes, estimated_ltf_candles_held,
                        session_guess, session_confidence, note, created_at, updated_at
                    from trades
                    """);
            statement.execute("drop table trades");
            statement.execute("alter table trades__migration rename to trades");
            statement.execute("COMMIT");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException ex) {
            try (Statement rollback = connection.createStatement()) {
                rollback.execute("ROLLBACK");
                rollback.execute("PRAGMA foreign_keys=ON");
            } catch (SQLException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            throw ex;
        }
    }

    private void migratePostgres(Connection connection) throws SQLException {
        if (!tableExists(connection, "trades")) {
            return;
        }

        boolean entryTimeNotNull = postgresColumnNotNull(connection, "trades", "entry_time");
        boolean exitTimeNotNull = postgresColumnNotNull(connection, "trades", "exit_time");
        if (!entryTimeNotNull && !exitTimeNotNull) {
            return;
        }

        log.info("Migrating PostgreSQL trades table to allow nullable entry_time/exit_time.");
        try (Statement statement = connection.createStatement()) {
            statement.execute("alter table trades alter column entry_time drop not null");
            statement.execute("alter table trades alter column exit_time drop not null");
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

    private Map<String, Boolean> sqliteNotNullColumns(Connection connection, String tableName) throws SQLException {
        Map<String, Boolean> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString("name"), rs.getInt("notnull") == 1);
            }
        }
        return result;
    }

    private boolean postgresColumnNotNull(Connection connection, String tableName, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select 1
                from information_schema.columns
                where table_name = ?
                  and column_name = ?
                  and is_nullable = 'NO'
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
