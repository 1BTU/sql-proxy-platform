package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.controller.PublicQueryController;
import com.example.dmdatabaseapi.entity.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration suite against an isolated schema_scope_test PostgreSQL database. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/pg-schema-integration.db",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
@EnabledIfEnvironmentVariable(named = "SQL_PROXY_PG_TEST_URL", matches = ".+")
class PgSchemaIntegrationTest {
    @Autowired private ProxyService proxies;
    @Autowired private ProxyQueryService queries;
    @Autowired private TargetDataSourceManager pools;
    @Autowired private PublicQueryController controller;

    @BeforeAll
    static void prepare() throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getenv("SQL_PROXY_PG_TEST_URL"),
                "postgres", System.getenv("SQL_PROXY_PG_TEST_PASSWORD")); Statement sql = connection.createStatement()) {
            try (var result = sql.executeQuery("SELECT current_database()")) {
                assertTrue(result.next());
                assertEquals("schema_scope_test", result.getString(1), "Only run against a dedicated test database");
            }
            sql.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='scope_reader') THEN "
                    + "CREATE ROLE scope_reader LOGIN PASSWORD 'scope_reader_local'; END IF; END $$");
            for (String schema : List.of("a", "b", "c", "Mixed Case", "a.b", "no_usage")) {
                sql.execute("CREATE SCHEMA IF NOT EXISTS " + PgSchemaPolicy.quoteIdentifier(schema));
                sql.execute("CREATE TABLE IF NOT EXISTS " + PgSchemaPolicy.quoteIdentifier(schema)
                        + ".report(id int PRIMARY KEY, source text)");
                try (var insert = connection.prepareStatement("INSERT INTO " + PgSchemaPolicy.quoteIdentifier(schema)
                        + ".report VALUES(1, ?) ON CONFLICT(id) DO UPDATE SET source=EXCLUDED.source")) {
                    insert.setString(1, schema); insert.executeUpdate();
                }
                if (!schema.equals("no_usage")) {
                    sql.execute("GRANT USAGE ON SCHEMA " + PgSchemaPolicy.quoteIdentifier(schema) + " TO scope_reader");
                    sql.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + PgSchemaPolicy.quoteIdentifier(schema) + " TO scope_reader");
                }
            }
            sql.execute("CREATE OR REPLACE VIEW a.report_view AS SELECT * FROM b.report");
            sql.execute("GRANT SELECT ON a.report_view TO scope_reader");
            sql.execute("CREATE TABLE IF NOT EXISTS a.\"a\"\"b\" (id int)");
            sql.execute("GRANT SELECT ON a.\"a\"\"b\" TO scope_reader");
            sql.execute("CREATE SCHEMA IF NOT EXISTS dianxin");
            sql.execute("CREATE TABLE IF NOT EXISTS dianxin.dx_ods_jcj "
                    + "(jjbh varchar PRIMARY KEY, jjsj varchar(14), cjxl varchar, jjssfxj varchar, cjlbdm varchar)");
            try (var insert = connection.prepareStatement("INSERT INTO dianxin.dx_ods_jcj VALUES(?,?,?,?,?) ON CONFLICT(jjbh) DO NOTHING")) {
                int id = 0;
                for (String date : List.of("20260928010000", "20261002010000", "20260921010000", "20260914010000", "20260907010000", "20260831010000")) {
                    insert.setString(1, "test-" + ++id); insert.setString(2, date); insert.setString(3, "家庭暴力");
                    insert.setString(4, "测试分局"); insert.setString(5, "02030201"); insert.executeUpdate();
                }
            }
            sql.execute("GRANT USAGE ON SCHEMA dianxin TO scope_reader");
            sql.execute("GRANT SELECT ON dianxin.dx_ods_jcj TO scope_reader");
        }
    }

    private ProxyRequest request(String name, String schema) {
        // The fixture URL uses a standard host:port/database shape without credentials or properties.
        var uri = java.net.URI.create(System.getenv("SQL_PROXY_PG_TEST_URL").substring(5));
        return new ProxyRequest(name, "postgresql", uri.getHost(), uri.getPort(),
                "schema_scope_test", schema, "scope_reader", "scope_reader_local", 1000, 30);
    }

    private ProxyService.TokenResult published(String schema) {
        var proxy = proxies.create(request("PG test " + UUID.randomUUID(), schema));
        proxies.test(proxy.id());
        return proxies.publish(proxy.id());
    }

    private ProxyConfig config(ProxyService.TokenResult token) {
        return proxies.findPublishedByToken(token.token());
    }

    private Object source(ProxyConfig config, String sql) throws Exception {
        return queries.execute(config, sql).rows().get(0).get("source");
    }

    @Test
    void resolvesDefaultTableAndBlocksAllOtherSchemasThroughPublicApi() throws Exception {
        var token = published("a");
        var config = config(token);
        assertEquals("a", source(config, "SELECT * FROM report"));
        assertEquals("a", source(config, "SELECT * FROM a.report"));
        assertEquals("a", source(config, "SELECT report.* FROM report"));
        assertEquals("a", source(config, "SELECT t.* FROM report t"));
        for (String sql : List.of("SELECT * FROM b.report", "SELECT * FROM c.report",
                "WITH x AS (SELECT * FROM b.report) SELECT * FROM x",
                "SELECT * FROM report WHERE EXISTS (SELECT 1 FROM c.report)",
                "SELECT * FROM report UNION ALL SELECT * FROM b.report")) {
            assertEquals(403, controller.query("Bearer " + token.token(), new PublicQueryRequest(sql, "json"))
                    .getStatusCode().value(), sql);
        }
        // Allowed view: indirect access follows the database permissions by design.
        assertEquals("b", source(config, "SELECT * FROM report_view"));
        assertEquals(1, queries.execute(config, "SELECT 1").rows().size());
        assertEquals("a", source(config, "WITH report AS (SELECT * FROM report) SELECT * FROM report"));
    }

    @Test
    void resetsSessionOnReuseAndChangesScopeWhenProxyIsEdited() throws Exception {
        var token = published("a");
        var before = config(token);
        var pool = pools.get(before, proxies.decryptPassword(before));
        assertSame(pool, pools.get(before, proxies.decryptPassword(before)));
        queries.execute(before, "SELECT set_config('search_path', 'b', false)");
        assertEquals("a", queries.execute(before, "SELECT current_schema() AS schema").rows().get(0).get("schema"));
        assertEquals("a", source(before, "SELECT * FROM report"));

        proxies.update(before.id(), request(before.name(), "b"));
        assertThrows(SecurityException.class, () -> proxies.findPublishedByToken(token.token()));
        proxies.test(before.id());
        var afterToken = proxies.publish(before.id());
        assertEquals(token.token(), afterToken.token());
        var after = config(afterToken);
        assertEquals("b", source(after, "SELECT * FROM report"));
        assertNotSame(pool, pools.get(after, proxies.decryptPassword(after)));
        assertThrows(SecurityException.class, () -> queries.execute(after, "SELECT * FROM a.report"));
    }

    @Test
    void supportsCaseSensitiveAndSpecialIdentifiers() throws Exception {
        var mixed = config(published("Mixed Case"));
        assertEquals("Mixed Case", source(mixed, "SELECT * FROM report"));
        assertEquals("Mixed Case", queries.execute(mixed, "SELECT current_schema() AS schema").rows().get(0).get("schema"));
        assertEquals("a.b", source(config(published("a.b")), "SELECT * FROM report"));
        assertEquals(0, queries.execute(config(published("a")), "SELECT * FROM \"a\"\"b\"").rows().size());
    }

    @Test
    void rejectsMissingOrInaccessibleSchemaDuringTest() {
        var missing = proxies.create(request("PG missing " + UUID.randomUUID(), "missing"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> proxies.test(missing.id())).getMessage().contains("不存在"));
        var denied = proxies.create(request("PG denied " + UUID.randomUUID(), "no_usage"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> proxies.test(denied.id())).getMessage().contains("USAGE"));
        assertEquals("DRAFT", proxies.get(denied.id()).status());
        assertThrows(IllegalArgumentException.class, () -> proxies.create(request("PG invalid", "a,b")));
    }

    @Test
    void emptySchemaPreservesCrossSchemaAccess() throws Exception {
        var config = config(published(null));
        assertEquals("b", source(config, "SELECT * FROM b.report"));
        assertEquals("c", source(config, "SELECT * FROM c.report"));
    }

    @Test
    void unicodeLabelsCtesAndNativeJsonOperatorsExecuteOnRealPg() throws Exception {
        var scoped = config(published("a"));
        var unscoped = config(published(null));
        for (var config : List.of(scoped, unscoped)) {
            var row = queries.execute(config,
                    "SELECT SUBSTR('20260928000000',1,8) AS 日期, COUNT(DISTINCT id) AS 警情数 "
                            + "FROM a.report WHERE source='a' GROUP BY SUBSTR('20260928000000',1,8) ORDER BY 日期").rows().get(0);
            assertEquals("20260928", row.get("日期"));
            assertEquals("1", row.get("警情数").toString());
            assertEquals("a", queries.execute(config,
                    "WITH 中文统计 AS (SELECT source AS 来源 FROM a.report) SELECT 来源 FROM 中文统计").rows().get(0).get("来源"));
            assertEquals("a", queries.execute(config, "SELECT source AS \"来源\" FROM a.report").rows().get(0).get("来源"));
            assertEquals("?; SELECT INTO; FOR UPDATE", queries.execute(config,
                    "SELECT $$?; SELECT INTO; FOR UPDATE$$ AS 描述").rows().get(0).get("描述"));
            var json = queries.execute(config,
                    "SELECT '{\"name\":1}'::jsonb ? 'name' AS 存在, "
                            + "'{\"name\":1}'::jsonb ?| ARRAY['name','missing'] AS 任一, "
                            + "'{\"name\":1}'::jsonb ?& ARRAY['name'] AS 全部 FROM a.report").rows().get(0);
            assertEquals(true, json.get("存在"));
            assertEquals(true, json.get("任一"));
            assertEquals(true, json.get("全部"));
        }
        var token = published("a");
        String sql = "WITH 中文统计 AS (SELECT * FROM b.report) SELECT * FROM 中文统计";
        assertEquals(403, controller.query("Bearer " + token.token(), new PublicQueryRequest(sql, "json"))
                .getStatusCode().value());
    }

    @Test
    void allReportedIncidentQueriesReturnSuccessViaPublicController() throws Exception {
        for (String schema : new String[]{null, "dianxin"}) {
            var token = published(schema);
            var config = config(token);
            for (String sql : ReadQueryCompatibilityTest.REPORTED_QUERIES) {
                var response = controller.query("Bearer " + token.token(), new PublicQueryRequest(sql, "json"));
                assertEquals(200, response.getStatusCode().value(), sql);
                assertEquals(200, ((ApiResponse) response.getBody()).getCode());
            }
            var weekly = queries.execute(config, ReadQueryCompatibilityTest.WEEK_QUERY).rows().get(0);
            assertEquals("2", weekly.get("本期警情数").toString());
            assertEquals(0, new java.math.BigDecimal(weekly.get("前四周均值").toString()).compareTo(java.math.BigDecimal.ONE));
            assertEquals(0, new java.math.BigDecimal(weekly.get("变化率_pct").toString()).compareTo(new java.math.BigDecimal("100")));
        }
    }
}
