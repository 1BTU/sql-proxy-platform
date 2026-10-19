package com.example.dmdatabaseapi.repository;

import com.example.dmdatabaseapi.entity.ProxyToken;
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

@Repository
public class ProxyTokenRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ProxyToken> mapper = (rs, rowNum) -> new ProxyToken(
            rs.getLong("id"), rs.getLong("proxy_id"), rs.getString("remark"),
            rs.getString("token_hash"), rs.getString("token_cipher"),
            rs.getString("created_at"), rs.getString("updated_at"));

    public ProxyTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ProxyToken> findByProxyId(long proxyId) {
        return jdbcTemplate.query("SELECT * FROM proxy_token WHERE proxy_id = ? ORDER BY id", mapper, proxyId);
    }

    public Optional<ProxyToken> findById(long proxyId, long tokenId) {
        return jdbcTemplate.query("SELECT * FROM proxy_token WHERE proxy_id = ? AND id = ?", mapper, proxyId, tokenId)
                .stream().findFirst();
    }

    public Optional<ProxyToken> findFirst(long proxyId) {
        return jdbcTemplate.query("SELECT * FROM proxy_token WHERE proxy_id = ? ORDER BY id LIMIT 1", mapper, proxyId)
                .stream().findFirst();
    }

    public int count(long proxyId) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM proxy_token WHERE proxy_id = ?", Integer.class, proxyId);
        return value == null ? 0 : value;
    }

    public ProxyToken create(long proxyId, String remark, String tokenHash, String tokenCipher) {
        String now = Instant.now().toString();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO proxy_token(proxy_id, remark, token_hash, token_cipher, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, proxyId);
            statement.setString(2, remark);
            statement.setString(3, tokenHash);
            statement.setString(4, tokenCipher);
            statement.setString(5, now);
            statement.setString(6, now);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("创建 Token 后未返回主键");
        return findById(proxyId, key.longValue()).orElseThrow();
    }

    public void updateRemark(long proxyId, long tokenId, String remark) {
        jdbcTemplate.update("UPDATE proxy_token SET remark = ?, updated_at = ? WHERE proxy_id = ? AND id = ?",
                remark, Instant.now().toString(), proxyId, tokenId);
    }

    public void regenerate(long proxyId, long tokenId, String tokenHash, String tokenCipher) {
        jdbcTemplate.update("UPDATE proxy_token SET token_hash = ?, token_cipher = ?, updated_at = ? WHERE proxy_id = ? AND id = ?",
                tokenHash, tokenCipher, Instant.now().toString(), proxyId, tokenId);
    }

    public void delete(long proxyId, long tokenId) {
        jdbcTemplate.update("DELETE FROM proxy_token WHERE proxy_id = ? AND id = ?", proxyId, tokenId);
    }

    public void deleteByProxyId(long proxyId) {
        jdbcTemplate.update("DELETE FROM proxy_token WHERE proxy_id = ?", proxyId);
    }
}
