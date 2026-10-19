package com.example.dmdatabaseapi.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Additive migration for existing SQLite installations; never rewrites saved data. */
@Component
@DependsOnDatabaseInitialization
public class PlatformSchemaMigration {
    private final JdbcTemplate jdbc;

    public PlatformSchemaMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void migrate() {
        boolean exists = jdbc.queryForList("PRAGMA table_info(proxy_config)").stream()
                .anyMatch(column -> "oracle_connection_mode".equals(column.get("name")));
        if (!exists) jdbc.execute("ALTER TABLE proxy_config ADD COLUMN oracle_connection_mode TEXT");
    }
}
