package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OracleSupportTest {
    static ProxyConfig proxy(String schema, String mode) {
        return new ProxyConfig(1, "Oracle test", "oracle", "db.local", 1521, "orcl", schema,
                "reader", "cipher", 1000, 30, "PUBLISHED", 1, 1, null, null, null, null, "now", "now", mode);
    }

    @Test
    void buildsBothThinUrlsAndLoadsBundledDriver() throws Exception {
        assertEquals("jdbc:oracle:thin:@//db.local:1521/orcl", new TargetDataSourceManager().jdbcUrl(proxy(null, null)));
        assertEquals("jdbc:oracle:thin:@db.local:1521:orcl", OracleSupport.jdbcUrl(proxy(null, "SID")));
        assertEquals("SERVICE_NAME", OracleSupport.connectionMode("oracle", null));
        assertNull(OracleSupport.connectionMode("dm", "SID"));
        assertThrows(IllegalArgumentException.class, () -> OracleSupport.connectionMode("oracle", "FULL_URL"));
        assertNotNull(Class.forName("oracle.jdbc.OracleDriver"));
        assertEquals("SELECT 1 AS RESULT FROM DUAL", OracleSupport.testSql("oracle"));
        assertEquals("SELECT 1", OracleSupport.testSql("postgresql"));
    }

    @Test
    void enforcesOracleIdentifierRulesAndTableScope() {
        assertEquals("HR", OracleSupport.configuredSchema("oracle", "hr"));
        assertEquals("hr", OracleSupport.configuredSchema("oracle", "\"hr\""));
        assertThrows(IllegalArgumentException.class, () -> OracleSupport.configuredSchema("oracle", "HR,SALES"));
        assertEquals("SELECT * FROM \"HR\".employees", rewrite("SELECT * FROM employees"));
        assertEquals("SELECT * FROM hr.employees", rewrite("SELECT * FROM hr.employees"));
        assertEquals("SELECT 1 AS RESULT FROM SYS.DUAL", rewrite("SELECT 1 AS RESULT FROM DUAL"));
        assertEquals("SELECT * FROM SYS.DUAL", rewrite("SELECT * FROM SYS.DUAL"));
        assertThrows(SecurityException.class, () -> rewrite("SELECT * FROM SALES.orders"));
        assertThrows(SecurityException.class, () -> rewrite("SELECT * FROM SYS.ALL_USERS"));
        assertThrows(SecurityException.class, () -> rewrite("SELECT * FROM employees@remote_db"));
        assertThrows(SecurityException.class, () -> rewrite("SELECT * FROM hr.\"employees\"@remote_db"));
        assertThrows(SecurityException.class, () -> PgSchemaPolicy.apply("SELECT * FROM employees@remote_db", proxy(null, null)));
        assertThrows(SecurityException.class, () -> rewrite("SELECT * FROM employees JOIN SALES.orders o ON o.id=employees.id"));
        assertThrows(SecurityException.class, () -> rewrite("SELECT * FROM employees WHERE EXISTS (SELECT 1 FROM SALES.orders)"));
        assertThrows(SecurityException.class, () -> rewrite("WITH x AS (SELECT * FROM SALES.orders) SELECT * FROM x"));
        assertThrows(SecurityException.class, () -> rewrite("SELECT * FROM employees UNION ALL SELECT * FROM SALES.orders"));
        assertDoesNotThrow(() -> rewrite("SELECT * FROM employees WHERE ROWNUM <= 10"));
        assertDoesNotThrow(() -> rewrite("SELECT 'email@server' AS address FROM DUAL /* employees@remote */"));
        assertEquals("SELECT q'[it's @ literal]' FROM DUAL",
                PgSchemaPolicy.sqlForParsing("SELECT q'[it's @ literal]' FROM DUAL", true));
        assertEquals("SELECT * FROM \"HR\".private_synonym", rewrite("SELECT * FROM private_synonym"));
        assertEquals("SELECT * FROM \"hr\".employees",
                PgSchemaPolicy.apply("SELECT * FROM employees", proxy("\"hr\"", null)));
        assertThrows(SecurityException.class, () -> rewrite("SELECT * FROM \"hr\".employees"));
    }

    @Test
    void ctesAndDualRemainCompatibleWith11g() {
        assertTrue(rewrite("WITH x AS (SELECT 1 AS n FROM DUAL) SELECT * FROM x").endsWith("SELECT * FROM x"));
        String recursive = rewrite("WITH x(n) AS (SELECT 1 FROM DUAL UNION ALL SELECT n+1 FROM x WHERE n<3) SELECT * FROM x");
        assertFalse(recursive.contains("\"HR\".x"));
        String shadow = rewrite("WITH DUAL AS (SELECT 1 AS n FROM HR.employees) SELECT * FROM DUAL");
        assertTrue(shadow.endsWith("SELECT * FROM DUAL"));
        assertEquals("SELECT * FROM \"HR\".\"a@b\"", rewrite("SELECT * FROM \"a@b\""));
        assertEquals("SELECT * FROM \"HR\".\"a\"\"b\"", rewrite("SELECT * FROM \"a\"\"b\""));
        assertDoesNotThrow(() -> PgSchemaPolicy.apply("SELECT * FROM SALES.orders", proxy(null, null)));
    }

    private String rewrite(String sql) {
        return PgSchemaPolicy.apply(new SqlSafetyValidator().validate(sql, proxy("HR", null)), proxy("HR", null));
    }

    @Test
    void transactionStartsReadOnlyAndRestoresConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        try (var ignored = OracleSupport.beginReadOnly(connection, 30)) { }
        InOrder order = inOrder(connection, statement);
        order.verify(connection).getAutoCommit();
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).createStatement();
        order.verify(statement).setQueryTimeout(30);
        order.verify(statement).execute("SET TRANSACTION READ ONLY");
        order.verify(statement).close();
        order.verify(connection).rollback();
        order.verify(connection).setAutoCommit(true);
        verify(connection, never()).commit();
        verify(connection, never()).setReadOnly(anyBoolean());
    }

    @Test
    void failedTransactionSetupStillRollsBackAndRestoresConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("test failure"));
        assertThrows(SQLException.class, () -> OracleSupport.beginReadOnly(connection, 30));
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection, never()).commit();
    }

    @Test
    void queryFailureRollsBackAndRestoresPooledConnection() throws Exception {
        var pools = mock(TargetDataSourceManager.class);
        var proxies = mock(ProxyService.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var setup = mock(Statement.class);
        var query = mock(Statement.class);
        when(proxies.decryptPassword(any())).thenReturn("secret");
        when(pools.get(any(), anyString())).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(setup, setup, query);
        when(query.executeQuery(anyString())).thenThrow(new SQLException("ORA-00942", "42000", 942));
        var service = new ProxyQueryService(new SqlSafetyValidator(), pools, proxies);
        assertThrows(SQLException.class, () -> service.execute(proxy("HR", null), "SELECT 1 FROM DUAL"));
        verify(connection).rollback(); verify(connection).setAutoCommit(true); verify(connection).close();
        verify(query).setMaxRows(1000); verify(query).setQueryTimeout(30);
        verify(connection, never()).setReadOnly(anyBoolean());
        verify(connection, never()).commit();
    }

    @Test
    void driverSpecificValuesBecomeStableJsonValues() throws Exception {
        assertEquals(new BigDecimal("123.45"), ProxyQueryService.normalizeJdbcValue(new oracle.sql.NUMBER(new BigDecimal("123.45"))));
        var timestamp = Timestamp.valueOf("2026-10-09 10:20:30");
        assertEquals("2026-10-09T10:20:30", ProxyQueryService.normalizeJdbcValue(new oracle.sql.DATE(timestamp)));
        assertEquals("2026-10-09T10:20:30", ProxyQueryService.normalizeJdbcValue(new oracle.sql.TIMESTAMP(timestamp)));
        var rowId = new oracle.sql.ROWID("AAAPr9AAEAAAACXAAA".getBytes(StandardCharsets.US_ASCII));
        assertEquals(rowId.stringValue(), ProxyQueryService.normalizeJdbcValue(rowId));
    }

    @Test
    void emptySchemaUsesServerSessionUserRatherThanClientUsernameCase() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var result = mock(java.sql.ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn("READER");
        OracleSupport.setDefaultSchema(connection, proxy(null, null));
        verify(statement).execute("ALTER SESSION SET CURRENT_SCHEMA = \"READER\"");
        verify(connection, never()).getMetaData();
    }
}
