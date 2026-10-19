package com.example.dmdatabaseapi.service;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.example.dmdatabaseapi.controller.PublicQueryController;
import com.example.dmdatabaseapi.entity.PublicQueryRequest;
import com.example.dmdatabaseapi.entity.ApiResponse;

class ProxyQueryServiceTest {

    @Test
    void forwardsFullSqlAndNativePgJsonOperatorsWithoutParameterBinding() throws Exception {
        var pools = mock(TargetDataSourceManager.class);
        var proxies = mock(ProxyService.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var result = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        when(proxies.decryptPassword(any())).thenReturn("not-used");
        when(pools.get(any(), anyString())).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("结果");
        when(result.next()).thenReturn(true, false);
        when(result.getObject(1)).thenReturn(true);
        String sql = "SELECT '{\"name\":1}'::jsonb ? 'name' AS 结果";
        var service = new ProxyQueryService(new SqlSafetyValidator(), pools, proxies);
        assertEquals(true, service.execute(ReadQueryCompatibilityTest.proxy("postgresql", null), sql)
                .rows().get(0).get("结果"));
        verify(statement).executeQuery(sql);
        verify(statement).setQueryTimeout(30);
        verify(statement).setMaxRows(1000);
        verify(connection, never()).prepareStatement(anyString());
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void normalizesJdbcValuesBeforeConnectionIsClosed() throws Exception {
        assertEquals("达梦文本", ProxyQueryService.normalizeJdbcValue(new SerialClob("达梦文本".toCharArray())));
        assertEquals("AQID", ProxyQueryService.normalizeJdbcValue(new SerialBlob(new byte[]{1, 2, 3})));
        assertEquals("AQID", ProxyQueryService.normalizeJdbcValue(new byte[]{1, 2, 3}));
        assertEquals("2026-09-18T14:00", ProxyQueryService.normalizeJdbcValue(
                Timestamp.valueOf(LocalDateTime.of(2026, 9, 18, 14, 0))));
        assertEquals("driver-value", ProxyQueryService.normalizeJdbcValue(new Object() {
            @Override public String toString() { return "driver-value"; }
        }));
    }

    @Test
    void reportedDmQueriesReachJdbcThroughPublicApiWithoutRewriting() throws Exception {
        for (String sql : ReadQueryCompatibilityTest.REPORTED_QUERIES) {
            var pools = mock(TargetDataSourceManager.class);
            var proxies = mock(ProxyService.class);
            var dataSource = mock(DataSource.class);
            var connection = mock(Connection.class);
            var statement = mock(Statement.class);
            var result = mock(ResultSet.class);
            var metadata = mock(ResultSetMetaData.class);
            var proxy = ReadQueryCompatibilityTest.proxy("dm", "DIANXIN");
            when(proxies.findPublishedByToken("dbp_local_test_only")).thenReturn(proxy);
            when(proxies.decryptPassword(any())).thenReturn("not-used");
            when(pools.get(any(), anyString())).thenReturn(dataSource);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(result);
            when(result.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(1);
            when(metadata.getColumnLabel(1)).thenReturn("警情数");
            when(result.next()).thenReturn(true, false);
            when(result.getObject(1)).thenReturn(2);
            var queryService = new ProxyQueryService(new SqlSafetyValidator(), pools, proxies);
            var controller = new PublicQueryController(proxies, queryService);
            var response = controller.query("Bearer dbp_local_test_only", new PublicQueryRequest(sql, "json"));
            assertEquals(200, response.getStatusCode().value());
            assertEquals(200, ((ApiResponse<?>) response.getBody()).getCode());
            verify(statement).executeQuery(sql);
            verify(statement).setQueryTimeout(30);
            verify(statement).setMaxRows(1000);
            verify(connection).close();
        }
    }
}
