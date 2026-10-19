package com.example.dmdatabaseapi.repository;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import com.example.dmdatabaseapi.entity.ProxyRequest;
import com.example.dmdatabaseapi.service.OracleSupport;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 持久化代理配置、测试状态和访问令牌。 */
@Repository
@DependsOn("platformSchemaMigration")
public class ProxyRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ProxyConfig> mapper = (rs, rowNum) -> new ProxyConfig(
            rs.getLong("id"), rs.getString("name"), rs.getString("db_type"),
            rs.getString("host"), rs.getInt("port"), rs.getString("database_name"),
            rs.getString("schema_name"), rs.getString("username"), rs.getString("password_cipher"),
            rs.getInt("max_rows"), rs.getInt("query_timeout_seconds"), rs.getString("status"),
            rs.getInt("config_version"), (Integer) rs.getObject("tested_version"),
            rs.getString("last_test_message"), rs.getString("last_test_at"),
            rs.getString("token_hash"), rs.getString("token_cipher"),
            rs.getString("created_at"), rs.getString("updated_at"), rs.getString("oracle_connection_mode"));

    public ProxyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProxyConfig> findAll() {
        return jdbcTemplate.query("SELECT * FROM proxy_config ORDER BY id DESC", mapper);
    }

    public Optional<ProxyConfig> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM proxy_config WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    public Optional<ProxyConfig> findPublishedByTokenHash(String hash) {
        return jdbcTemplate.query("""
                SELECT p.* FROM proxy_config p
                JOIN proxy_token t ON t.proxy_id = p.id
                WHERE t.token_hash = ? AND p.status = 'PUBLISHED'
                """, mapper, hash)
                .stream().findFirst();
    }

    public long create(ProxyRequest request, String passwordCipher) {
        String now = Instant.now().toString();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO proxy_config(
                        name, db_type, host, port, database_name, schema_name, username, password_cipher,
                        max_rows, query_timeout_seconds, oracle_connection_mode, status, config_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bindRequest(ps, request, passwordCipher);
            ps.setString(12, now);
            ps.setString(13, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建代理后未返回主键");
        }
        return key.longValue();
    }

    public void update(long id, ProxyRequest request, String passwordCipher) {
        jdbcTemplate.update("""
                UPDATE proxy_config SET
                    name = ?, db_type = ?, host = ?, port = ?, database_name = ?, schema_name = ?,
                    username = ?, password_cipher = ?, max_rows = ?, query_timeout_seconds = ?, oracle_connection_mode = ?,
                    status = 'DRAFT', config_version = config_version + 1, tested_version = NULL,
                    last_test_message = NULL, last_test_at = NULL, updated_at = ?
                WHERE id = ?
                """, request.name(), request.dbType(), request.host(), request.port(), request.database(),
                blankToNull(request.schema()), request.username(), passwordCipher, request.maxRows(),
                request.queryTimeoutSeconds(), OracleSupport.connectionMode(request.dbType(), request.oracleConnectionMode()),
                Instant.now().toString(), id);
    }

    public void markTested(long id, int version, String message) {
        jdbcTemplate.update("""
                UPDATE proxy_config SET
                    status = CASE WHEN status = 'PUBLISHED' THEN 'PUBLISHED' ELSE 'TESTED' END,
                    tested_version = ?, last_test_message = ?,
                    last_test_at = ?, updated_at = ? WHERE id = ? AND config_version = ?
                """, version, message, Instant.now().toString(), Instant.now().toString(), id, version);
    }

    public void markTestFailed(long id, String message) {
        jdbcTemplate.update("""
                UPDATE proxy_config SET status = 'DRAFT', tested_version = NULL, last_test_message = ?,
                    last_test_at = ?, updated_at = ? WHERE id = ?
                """, message, Instant.now().toString(), Instant.now().toString(), id);
    }

    public void publish(long id, String tokenHash, String tokenCipher) {
        jdbcTemplate.update("""
                UPDATE proxy_config SET status = 'PUBLISHED', token_hash = ?, token_cipher = ?, updated_at = ?
                WHERE id = ?
                """, tokenHash, tokenCipher, Instant.now().toString(), id);
    }

    public void unpublish(long id) {
        jdbcTemplate.update("UPDATE proxy_config SET status = 'TESTED', updated_at = ? WHERE id = ?",
                Instant.now().toString(), id);
    }

    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM proxy_config WHERE id = ?", id);
    }

    private void bindRequest(PreparedStatement ps, ProxyRequest request, String passwordCipher) throws java.sql.SQLException {
        ps.setString(1, request.name());
        ps.setString(2, request.dbType());
        ps.setString(3, request.host());
        ps.setInt(4, request.port());
        ps.setString(5, request.database());
        ps.setString(6, blankToNull(request.schema()));
        ps.setString(7, request.username());
        ps.setString(8, passwordCipher);
        ps.setInt(9, request.maxRows());
        ps.setInt(10, request.queryTimeoutSeconds());
        ps.setString(11, OracleSupport.connectionMode(request.dbType(), request.oracleConnectionMode()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
