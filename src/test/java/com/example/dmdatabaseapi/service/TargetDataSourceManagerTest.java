package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TargetDataSourceManagerTest {
    private final TargetDataSourceManager manager = new TargetDataSourceManager();

    @Test
    void buildsSupportedJdbcUrls() {
        assertEquals("jdbc:mysql://db.local:3306/app?useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull&useSSL=false",
                manager.jdbcUrl(proxy("mysql", 3306, "app", null)));
        assertEquals("jdbc:postgresql://db.local:5432/app", manager.jdbcUrl(proxy("postgresql", 5432, "app", null)));
        assertEquals("jdbc:dm://db.local:5236?schema=REPORT", manager.jdbcUrl(proxy("dm", 5236, "app", "REPORT")));
    }

    private ProxyConfig proxy(String type, int port, String database, String schema) {
        return new ProxyConfig(1, "test", type, "db.local", port, database, schema, "reader", "cipher",
                1000, 30, "DRAFT", 1, null, null, null, null, null, "now", "now");
    }
}
