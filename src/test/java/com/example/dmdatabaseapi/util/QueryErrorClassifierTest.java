package com.example.dmdatabaseapi.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryErrorClassifierTest {

    @Test
    void classifiesOracleCharsetFailureInsidePoolAndJdbcWrappers() {
        assertError(new SQLException("secret", "99999", 17056), HttpStatus.SERVICE_UNAVAILABLE, "字符集");
        for (String message : new String[]{
                "Non supported character set (add orai18n.jar in your classpath): ZHS16GBK secret",
                "不支持的字符集 (在类路径中添加 orai18n.jar): ZHS16GBK secret"}) {
            var cause = new SQLException(message, "99999", 0);
            assertError(new RuntimeException("Pool initialization failed", cause),
                    HttpStatus.SERVICE_UNAVAILABLE, "无需修改数据库字符集");
            var wrapper = new SQLException("connection timeout secret", "08001");
            wrapper.setNextException(cause);
            assertError(wrapper, HttpStatus.SERVICE_UNAVAILABLE, "orai18n");
        }
    }

    @Test
    void classifiesOracleCodesAndNestedProtocolFailuresWithoutLeakingDetails() {
        for (int code : new int[]{904, 942, 933}) {
            assertError(new SQLException("secret", "42000", code), HttpStatus.BAD_REQUEST, "Oracle");
        }
        assertError(new SQLException("secret", "42000", 1031), HttpStatus.FORBIDDEN, "权限不足");
        for (int code : new int[]{1017, 12514, 12505, 12541}) {
            assertError(new SQLException("secret", "99999", code), HttpStatus.SERVICE_UNAVAILABLE, "Oracle");
        }
        var protocol = new SQLException("secret", "99999", 28040);
        var wrapper = new SQLException("connection timeout", "08001");
        wrapper.setNextException(protocol);
        var result = QueryErrorClassifier.classify(wrapper, "abc12345");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result.status());
        assertTrue(result.message().contains("认证协议"));
        assertFalse(result.message().contains("secret"));
        assertError(new SQLException("secret", "72000", 1013), HttpStatus.GATEWAY_TIMEOUT, "超时");
    }

    @Test
    void classifiesCommonDatabaseFailuresWithoutLeakingDriverMessage() {
        assertError(new SQLTimeoutException("socket timed out at jdbc:dm://secret"), HttpStatus.GATEWAY_TIMEOUT, "查询执行超时");
        assertError(new SQLException("permission denied for table payroll", "42501"), HttpStatus.FORBIDDEN, "无权访问");
        assertError(new SQLSyntaxErrorException("unknown column secret_column", "42000"), HttpStatus.BAD_REQUEST, "SQL 执行失败");
        assertError(new SQLException("connection refused jdbc:postgresql://secret", "08001"), HttpStatus.SERVICE_UNAVAILABLE, "连接中断");
    }

    private void assertError(Exception source, HttpStatus status, String expectedMessage) {
        QueryErrorClassifier.QueryError result = QueryErrorClassifier.classify(source, "abc12345");
        assertEquals(status, result.status());
        assertTrue(result.message().contains(expectedMessage));
        assertTrue(result.message().contains("abc12345"));
        assertFalse(result.message().contains("secret"));
    }
}
