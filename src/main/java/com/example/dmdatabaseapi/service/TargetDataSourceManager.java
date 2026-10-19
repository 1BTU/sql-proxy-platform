package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按代理配置版本缓存目标数据库连接池。 */
@Component
public class TargetDataSourceManager {
    private final Map<Long, VersionedDataSource> dataSources = new ConcurrentHashMap<>();

    public DataSource get(ProxyConfig proxy, String password) {
        VersionedDataSource current = dataSources.get(proxy.id());
        if (current != null && current.version() == proxy.configVersion()) {
            return current.dataSource();
        }
        synchronized (dataSources) {
            current = dataSources.get(proxy.id());
            if (current != null && current.version() == proxy.configVersion()) {
                return current.dataSource();
            }
            evict(proxy.id());
            HikariDataSource created = create(proxy, password, 8);
            dataSources.put(proxy.id(), new VersionedDataSource(proxy.configVersion(), created));
            return created;
        }
    }

    public HikariDataSource createForTest(ProxyConfig proxy, String password) {
        return create(proxy, password, 1);
    }

    public void evict(long id) {
        VersionedDataSource removed = dataSources.remove(id);
        if (removed != null) {
            removed.dataSource().close();
        }
    }

    public String jdbcUrl(ProxyConfig proxy) {
        String type = proxy.dbType().toLowerCase();
        return switch (type) {
            case "mysql" -> String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull&useSSL=false",
                    proxy.host(), proxy.port(), proxy.database());
            case "postgresql", "postgres" -> String.format("jdbc:postgresql://%s:%d/%s",
                    proxy.host(), proxy.port(), proxy.database());
            case "oracle" -> OracleSupport.jdbcUrl(proxy);
            case "dm", "dameng" -> String.format("jdbc:dm://%s:%d%s", proxy.host(), proxy.port(),
                    proxy.schema() == null || proxy.schema().isBlank() ? "" : "?schema=" + proxy.schema());
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + proxy.dbType());
        };
    }

    private HikariDataSource create(ProxyConfig proxy, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("proxy-" + proxy.id() + "-v" + proxy.configVersion());
        config.setJdbcUrl(jdbcUrl(proxy));
        config.setUsername(proxy.username());
        config.setPassword(password);
        config.setDriverClassName(driverClass(proxy.dbType()));
        String pgSchema = PgSchemaPolicy.configuredSchema(proxy.dbType(), proxy.schema());
        if (pgSchema != null) {
            config.addDataSourceProperty("currentSchema", PgSchemaPolicy.quoteIdentifier(pgSchema));
        }
        // 达梦部分 JDBC 驱动版本不完整支持 Connection#setReadOnly，查询层会做兼容性处理。
        config.setReadOnly(!isDm(proxy.dbType()) && !OracleSupport.isOracle(proxy.dbType()));
        if (OracleSupport.isOracle(proxy.dbType())) {
            config.setConnectionTestQuery(OracleSupport.testSql(proxy.dbType()));
        }
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10000);
        config.setValidationTimeout(5000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(900000);
        return new HikariDataSource(config);
    }

    private String driverClass(String type) {
        return switch (type.toLowerCase()) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql", "postgres" -> "org.postgresql.Driver";
            case "dm", "dameng" -> "dm.jdbc.driver.DmDriver";
            case "oracle" -> "oracle.jdbc.OracleDriver";
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + type);
        };
    }

    private boolean isDm(String type) {
        return "dm".equalsIgnoreCase(type) || "dameng".equalsIgnoreCase(type);
    }

    private record VersionedDataSource(int version, HikariDataSource dataSource) {
    }
}
