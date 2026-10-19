package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import com.example.dmdatabaseapi.entity.ProxyRequest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Oracle Thin connection, Oracle identifier rules and 11g-compatible read-only transactions. */
public final class OracleSupport {
    private OracleSupport() { }

    public static boolean isOracle(String type) { return "oracle".equalsIgnoreCase(type); }

    public static String connectionMode(String type, String mode) {
        if (!isOracle(type)) return null;
        String value = mode == null || mode.isBlank() ? "SERVICE_NAME" : mode.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("SERVICE_NAME") && !value.equals("SID")) {
            throw new IllegalArgumentException("Oracle 连接模式仅支持 SERVICE_NAME 或 SID");
        }
        return value;
    }

    public static String configuredSchema(String type, String schema) {
        if (!isOracle(type) || schema == null || schema.isBlank()) return null;
        String value = schema.trim();
        if (value.contains(",") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Oracle Schema 只能填写一个模式名");
        }
        if (value.matches("\"(?:[^\"]|\"\")+\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        if (!value.matches("[A-Za-z][A-Za-z0-9_$#]*")) {
            throw new IllegalArgumentException("Oracle Schema 名称无效；特殊字符或区分大小写的名称请使用双引号");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    public static void validate(ProxyRequest request) {
        if (!isOracle(request.dbType())) return;
        connectionMode(request.dbType(), request.oracleConnectionMode());
        configuredSchema(request.dbType(), request.schema());
        validateAddress(request.host(), request.database());
    }

    private static void validateAddress(String host, String database) {
        if (host == null || !host.matches("(?:[A-Za-z0-9_.:-]+|\\[[A-Fa-f0-9:.%]+\\])")) {
            throw new IllegalArgumentException("Oracle 主机地址无效，请填写主机名或 IP");
        }
        if (database == null || !database.matches("[A-Za-z0-9_.$#-]+")) {
            throw new IllegalArgumentException("Oracle Service Name/SID 无效，请只填写名称，不要填写完整连接串");
        }
    }

    public static String jdbcUrl(ProxyConfig proxy) {
        validateAddress(proxy.host(), proxy.database());
        String host = proxy.host();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        if (connectionMode(proxy.dbType(), proxy.oracleConnectionMode()).equals("SID")) {
            return "jdbc:oracle:thin:@" + host + ":" + proxy.port() + ":" + proxy.database();
        }
        return "jdbc:oracle:thin:@//" + host + ":" + proxy.port() + "/" + proxy.database();
    }

    public static String testSql(String type) {
        return isOracle(type) ? "SELECT 1 AS RESULT FROM DUAL" : "SELECT 1";
    }

    public static void checkSchema(Connection connection, ProxyConfig proxy) throws Exception {
        String schema = configuredSchema(proxy.dbType(), proxy.schema());
        if (schema == null) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT USERNAME FROM SYS.ALL_USERS WHERE USERNAME = ?")) {
            statement.setString(1, schema);
            statement.setQueryTimeout(Math.min(proxy.queryTimeoutSeconds(), 30));
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("配置的 Oracle Schema 不存在，请检查名称及大小写");
            }
        }
    }

    public static void setDefaultSchema(Connection connection, ProxyConfig proxy) throws Exception {
        if (!isOracle(proxy.dbType())) return;
        String schema = configuredSchema(proxy.dbType(), proxy.schema());
        // SESSION_USER is the actual database identifier, not the client-supplied username
        // (which may be lowercase or quoted). Restore it when no schema is configured.
        if (schema == null) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(proxy.queryTimeoutSeconds());
                try (var result = statement.executeQuery("SELECT SYS_CONTEXT('USERENV','SESSION_USER') FROM SYS.DUAL")) {
                    if (!result.next() || result.getString(1) == null) throw new SQLException("Oracle session user unavailable");
                    schema = result.getString(1);
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(proxy.queryTimeoutSeconds());
            statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + PgSchemaPolicy.quoteIdentifier(schema));
        }
    }

    public static ReadOnlyTransaction beginReadOnly(Connection connection, int timeoutSeconds) throws Exception {
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        ReadOnlyTransaction transaction = new ReadOnlyTransaction(connection, previous);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute("SET TRANSACTION READ ONLY");
        } catch (Exception error) {
            try { transaction.close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        return transaction;
    }

    public static final class ReadOnlyTransaction implements AutoCloseable {
        private final Connection connection;
        private final boolean previousAutoCommit;
        private ReadOnlyTransaction(Connection connection, boolean previousAutoCommit) {
            this.connection = connection;
            this.previousAutoCommit = previousAutoCommit;
        }
        @Override public void close() throws SQLException {
            try { connection.rollback(); }
            catch (SQLException failed) {
                // Never put a connection with an unclean transaction back into the pool.
                try { connection.abort(Runnable::run); } catch (SQLException abort) { failed.addSuppressed(abort); }
                throw failed;
            }
            connection.setAutoCommit(previousAutoCommit);
        }
    }
}
