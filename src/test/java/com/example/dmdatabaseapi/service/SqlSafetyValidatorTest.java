package com.example.dmdatabaseapi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlSafetyValidatorTest {
    private final SqlSafetyValidator validator = new SqlSafetyValidator();

    @Test
    void acceptsSelectAndReadOnlyCte() {
        assertEquals("SELECT * FROM users WHERE id = ?", validator.validate("SELECT * FROM users WHERE id = ?"));
        assertEquals("WITH x AS (SELECT 1 AS id) SELECT * FROM x",
                validator.validate("WITH x AS (SELECT 1 AS id) SELECT * FROM x"));
    }

    @Test
    void rejectsWritesMultipleStatementsAndLocks() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate("UPDATE users SET enabled = 0"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SELECT 1; DELETE FROM users"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SELECT * FROM users FOR UPDATE"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SELECT * INTO backup FROM users"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("WITH removed AS (DELETE FROM users RETURNING id) SELECT * FROM removed"));
    }
}
