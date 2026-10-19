package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PgSchemaPolicyTest {
    private static ProxyConfig proxy(String type, String schema) {
        return new ProxyConfig(1, "test", type, "localhost", 5432, "app", schema, "reader", "cipher",
                1000, 30, "PUBLISHED", 1, 1, null, null, null, null, "now", "now");
    }

    private String apply(String sql) { return PgSchemaPolicy.apply(sql, proxy("postgresql", "a")); }

    @Test
    void qualifiesBaseTablesWithoutChangingAliasesOrColumns() {
        assertEquals("SELECT t.id FROM \"a\".users t", apply("select t.id from users t"));
        assertEquals("SELECT users.* FROM \"a\".users", apply("select users.* from users"));
        assertEquals("SELECT * FROM a.users", apply("select * from a.users"));
        assertEquals("SELECT 1", apply("select 1"));
    }

    @Test
    void rejectsCrossSchemaInEveryQueryLocation() {
        for (String sql : new String[]{
                "SELECT * FROM b.users", "SELECT * FROM c.users", "SELECT * FROM app.a.users",
                "SELECT * FROM users JOIN b.users x ON users.id=x.id",
                "SELECT * FROM users WHERE EXISTS (SELECT 1 FROM b.users)",
                "SELECT * FROM users WHERE id IN (SELECT id FROM b.users)",
                "SELECT (SELECT COUNT(*) FROM b.users) FROM users",
                "SELECT CASE WHEN TRUE THEN (SELECT COUNT(*) FROM b.users) ELSE 0 END",
                "SELECT COUNT(*) FROM users GROUP BY id HAVING id IN (SELECT id FROM b.users)",
                "SELECT * FROM users ORDER BY (SELECT COUNT(*) FROM b.users)",
                "SELECT * FROM users UNION ALL SELECT * FROM b.users",
                "WITH x AS (SELECT * FROM b.users) SELECT * FROM x",
                "SELECT * FROM LATERAL (SELECT * FROM b.users) x",
                "SELECT * FROM (users JOIN b.users x ON users.id=x.id)"
        }) assertThrows(SecurityException.class, () -> apply(sql), sql);
    }

    @Test
    void respectsSequentialRecursiveAndNestedCteScopes() {
        String shadow = apply("WITH users AS (SELECT * FROM users) SELECT * FROM users");
        assertTrue(shadow.contains("SELECT * FROM \"a\".users"));
        assertTrue(shadow.endsWith("SELECT * FROM users"));
        String sequential = apply("WITH x AS (SELECT * FROM y), y AS (SELECT 1) SELECT * FROM x");
        assertTrue(sequential.contains("FROM \"a\".y"));
        String recursive = apply("WITH RECURSIVE x AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM x WHERE n<3) SELECT * FROM x");
        assertFalse(recursive.contains("\"a\".x"));
        String nested = apply("SELECT * FROM (WITH x AS (SELECT 1) SELECT * FROM x) z JOIN x ON TRUE");
        assertTrue(nested.contains("JOIN \"a\".x"));
        assertThrows(SecurityException.class, () -> apply("WITH b AS (SELECT 1) SELECT * FROM b.users"));
    }

    @Test
    void honorsQuotedIdentifiersAndIgnoresCommentsAndStrings() {
        assertEquals("SELECT 'b.users' FROM \"a\".users", apply("SELECT 'b.users' FROM users /* b.users */"));
        assertDoesNotThrow(() -> apply("SELECT * FROM A.users"));
        assertDoesNotThrow(() -> apply("SELECT * FROM \"a\".\"User\""));
        assertThrows(SecurityException.class, () -> apply("SELECT * FROM \"A\".users"));
        assertEquals("SELECT * FROM \"Mixed Case\".users",
                PgSchemaPolicy.apply("SELECT * FROM users", proxy("postgres", "Mixed Case")));
        assertEquals("SELECT * FROM \"a.b\".users",
                PgSchemaPolicy.apply("SELECT * FROM users", proxy("postgres", "a.b")));
        assertEquals("SELECT * FROM \"a\".\"a@b\"", apply("SELECT * FROM \"a@b\""));
        assertEquals("SELECT * FROM \"a\".\"a\"\"b\"", apply("SELECT * FROM \"a\"\"b\""));
        assertEquals("SELECT * FROM \"a\"\"b\".users",
                PgSchemaPolicy.apply("SELECT * FROM \"a\"\"b\".users", proxy("postgres", "a\"b")));
        assertThrows(SecurityException.class, () -> apply("SELECT * FROM \"b\"\"c\".users"));
        assertEquals("SELECT '\"a\"\"b\"' FROM \"a\".users", apply("SELECT '\"a\"\"b\"' FROM users"));
    }

    @Test
    void validatesSingleConfiguredSchemaAndPreservesOtherDatabaseBehavior() {
        assertThrows(IllegalArgumentException.class, () -> PgSchemaPolicy.configuredSchema("postgresql", "a,b"));
        assertThrows(IllegalArgumentException.class, () -> PgSchemaPolicy.configuredSchema("postgresql", "a\nSET x"));
        String sql = "select * from b.users";
        assertEquals(sql, PgSchemaPolicy.apply(sql, proxy("postgresql", null)));
        assertEquals(sql, PgSchemaPolicy.apply(sql, proxy("dm", "a")));
        assertEquals(sql, PgSchemaPolicy.apply(sql, proxy("mysql", "a")));
        assertThrows(IllegalArgumentException.class, () -> apply("not a select"));
    }
}
