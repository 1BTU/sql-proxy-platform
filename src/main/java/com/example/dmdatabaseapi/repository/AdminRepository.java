package com.example.dmdatabaseapi.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** 读写唯一的平台管理员账号。 */
@Repository
public class AdminRepository {
    private final JdbcTemplate jdbcTemplate;

    public AdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createIfMissing(String username, String passwordHash) {
        jdbcTemplate.update("""
                INSERT OR IGNORE INTO admin_user(id, username, password_hash, must_change_password, updated_at)
                VALUES (1, ?, ?, 1, ?)
                """, username, passwordHash, Instant.now().toString());
    }

    public Optional<AdminUser> findByUsername(String username) {
        return jdbcTemplate.query("""
                SELECT username, password_hash, must_change_password FROM admin_user WHERE username = ?
                """, (rs, rowNum) -> new AdminUser(
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getInt("must_change_password") == 1), username).stream().findFirst();
    }

    public Optional<AdminUser> findCurrent() {
        return jdbcTemplate.query("""
                SELECT username, password_hash, must_change_password FROM admin_user WHERE id = 1
                """, (rs, rowNum) -> new AdminUser(
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getInt("must_change_password") == 1)).stream().findFirst();
    }

    public void updatePassword(String passwordHash) {
        jdbcTemplate.update("""
                UPDATE admin_user SET password_hash = ?, must_change_password = 0, updated_at = ? WHERE id = 1
                """, passwordHash, Instant.now().toString());
    }

    public record AdminUser(String username, String passwordHash, boolean mustChangePassword) {
    }
}
