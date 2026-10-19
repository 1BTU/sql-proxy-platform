package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.config.PlatformSchemaMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlatformSchemaMigrationTest {
    @TempDir Path directory;

    @Test
    void addsColumnIdempotentlyWithoutChangingExistingSecretsOrTokens() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("legacy.db")));
        jdbc.execute("CREATE TABLE proxy_config(id INTEGER PRIMARY KEY, password_cipher TEXT, status TEXT)");
        jdbc.execute("CREATE TABLE admin_user(id INTEGER PRIMARY KEY, password_hash TEXT)");
        jdbc.execute("CREATE TABLE proxy_token(id INTEGER PRIMARY KEY, token_hash TEXT, token_cipher TEXT)");
        jdbc.update("INSERT INTO proxy_config VALUES(1, 'cipher-before', 'PUBLISHED')");
        jdbc.update("INSERT INTO admin_user VALUES(1, 'bcrypt-before')");
        jdbc.update("INSERT INTO proxy_token VALUES(1, 'hash-before', 'token-cipher-before')");
        var migration = new PlatformSchemaMigration(jdbc);
        migration.migrate();
        migration.migrate();
        assertNull(jdbc.queryForObject("SELECT oracle_connection_mode FROM proxy_config WHERE id=1", String.class));
        assertEquals("cipher-before", jdbc.queryForObject("SELECT password_cipher FROM proxy_config WHERE id=1", String.class));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM proxy_config WHERE id=1", String.class));
        assertEquals("bcrypt-before", jdbc.queryForObject("SELECT password_hash FROM admin_user WHERE id=1", String.class));
        assertEquals("token-cipher-before", jdbc.queryForObject("SELECT token_cipher FROM proxy_token WHERE id=1", String.class));
        assertEquals("hash-before", jdbc.queryForObject("SELECT token_hash FROM proxy_token WHERE id=1", String.class));
    }
}
