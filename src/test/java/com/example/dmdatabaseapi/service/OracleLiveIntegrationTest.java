package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Optional verification against the actual 11.2.0.1 database; no DDL or DML fixtures. */
@EnabledIfEnvironmentVariable(named = "SQL_PROXY_ORACLE_TEST_URL", matches = ".+")
class OracleLiveIntegrationTest {
    private DataSource dataSource() {
        return new DriverManagerDataSource(System.getenv("SQL_PROXY_ORACLE_TEST_URL"),
                System.getenv("SQL_PROXY_ORACLE_TEST_USERNAME"), System.getenv("SQL_PROXY_ORACLE_TEST_PASSWORD"));
    }

    private ProxyConfig proxy() {
        return new ProxyConfig(1, "Oracle live test", "oracle", "test", 1521, "test",
                System.getenv("SQL_PROXY_ORACLE_TEST_SCHEMA"), System.getenv("SQL_PROXY_ORACLE_TEST_USERNAME"), "cipher",
                1000, 30, "PUBLISHED", 1, 1, null, null, null, null, "now", "now", "SERVICE_NAME");
    }

    @Test
    void validatesTargetVersionAndReadOnlyTransaction() throws Exception {
        try (var connection = dataSource().getConnection()) {
            assertTrue(connection.getMetaData().getDatabaseProductVersion().contains("11.2.0.1"),
                    "This suite must verify Oracle 11.2.0.1, not substitute a newer database");
            OracleSupport.checkSchema(connection, proxy());
            OracleSupport.setDefaultSchema(connection, proxy());
            try (var transaction = OracleSupport.beginReadOnly(connection, 30);
                 var statement = connection.createStatement(); var result = statement.executeQuery("SELECT 1 FROM DUAL")) {
                assertTrue(result.next()); assertEquals(1, result.getInt(1));
            }
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void executesTyped11gResultsThroughProxyEngine() throws Exception {
        var pools = mock(TargetDataSourceManager.class);
        var proxies = mock(ProxyService.class);
        when(pools.get(any(), anyString())).thenReturn(dataSource());
        when(proxies.decryptPassword(any())).thenReturn("not-used");
        var query = new ProxyQueryService(new SqlSafetyValidator(), pools, proxies);
        String sql = "SELECT 1 AS N, TO_CLOB('oracle text') AS C, TO_NCLOB('unicode text') AS NC, "
                + "TO_BLOB(HEXTORAW('010203')) AS B, "
                + "TO_DATE('2026-10-09 10:20:30','YYYY-MM-DD HH24:MI:SS') AS D, "
                + "TIMESTAMP '2026-10-09 10:20:30' AS TS FROM DUAL";
        var result = query.execute(proxy(), sql).rows().get(0);
        assertEquals("1", result.get("N").toString());
        assertEquals("oracle text", result.get("C"));
        assertEquals("unicode text", result.get("NC"));
        assertEquals("AQID", result.get("B"));
        assertNotNull(result.get("D")); assertNotNull(result.get("TS"));
        assertThrows(SecurityException.class, () -> query.execute(proxy(), "SELECT * FROM DUAL@remote_db"));
    }
}
