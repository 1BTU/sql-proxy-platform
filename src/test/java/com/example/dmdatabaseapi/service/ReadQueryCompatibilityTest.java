package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReadQueryCompatibilityTest {
    static final String INCIDENT_QUERY = "SELECT SUBSTR(jjsj,1,8) AS 日期, COUNT(DISTINCT jjbh) AS 警情数 "
            + "FROM DIANXIN.dx_ods_jcj WHERE jjsj >= '20260928000000' "
            + "AND jjsj < '20261005000000' AND cjxl = '家庭暴力' "
            + "GROUP BY SUBSTR(jjsj,1,8) ORDER BY 日期";
    static final String REGION_QUERY = "SELECT jjssfxj AS 接警分局, COUNT(DISTINCT jjbh) AS 警情数 "
            + "FROM DIANXIN.dx_ods_jcj WHERE jjsj >= '20260928000000' AND jjsj < '20261005000000' "
            + "AND cjxl = '家庭暴力' GROUP BY jjssfxj ORDER BY 警情数 DESC";
    static final String WEEK_QUERY = "WITH w AS (SELECT "
            + "COUNT(DISTINCT CASE WHEN jjsj >= '20260928000000' AND jjsj < '20261005000000' THEN jjbh END) AS n0, "
            + "COUNT(DISTINCT CASE WHEN jjsj >= '20260921000000' AND jjsj < '20260928000000' THEN jjbh END) AS n1, "
            + "COUNT(DISTINCT CASE WHEN jjsj >= '20260914000000' AND jjsj < '20260921000000' THEN jjbh END) AS n2, "
            + "COUNT(DISTINCT CASE WHEN jjsj >= '20260907000000' AND jjsj < '20260914000000' THEN jjbh END) AS n3, "
            + "COUNT(DISTINCT CASE WHEN jjsj >= '20260831000000' AND jjsj < '20260907000000' THEN jjbh END) AS n4 "
            + "FROM DIANXIN.dx_ods_jcj WHERE jjsj >= '20260831000000' AND jjsj < '20261005000000' AND cjxl = '家庭暴力') "
            + "SELECT n0 AS 本期警情数, (n1+n2+n3+n4)/4.0 AS 前四周均值, n0 - (n1+n2+n3+n4)/4.0 AS 增减量, "
            + "ROUND((n0 - (n1+n2+n3+n4)/4.0)*100.0/NULLIF((n1+n2+n3+n4)/4.0,0),2) AS 变化率_pct, "
            + "n1 AS 前一周, n2 AS 前二周, n3 AS 前三周, n4 AS 前四周 FROM w";
    static final String CODE_WEEK_QUERY = WEEK_QUERY.replace("cjxl = '家庭暴力'",
            "cjlbdm IN ('02030201','02030202','02030203','02030204','02030205','02030299')");
    static final List<String> REPORTED_QUERIES = List.of(INCIDENT_QUERY, REGION_QUERY, WEEK_QUERY, CODE_WEEK_QUERY);
    private final SqlSafetyValidator validator = new SqlSafetyValidator();

    static ProxyConfig proxy(String type, String schema) {
        return new ProxyConfig(1, "compatibility", type, "localhost", 5432, "app", schema, "reader", "cipher",
                1000, 30, "PUBLISHED", 1, 1, null, null, null, null, "now", "now");
    }

    @Test
    void acceptsExactReportedSqlForEveryDatabaseWithoutChangingIt() {
        for (String type : List.of("dm", "dameng", "mysql", "postgres", "postgresql", "oracle")) {
            var config = proxy(type, null);
            for (String sql : REPORTED_QUERIES) {
                assertEquals(sql, validator.validate(sql, config));
                assertEquals(sql, PgSchemaPolicy.apply(sql, config));
            }
        }
    }

    @Test
    void leavesReadOnlyGrammarToDatabaseWhenThereIsNoSchemaRestriction() {
        for (String type : List.of("dm", "mysql", "postgresql", "oracle")) {
            for (String sql : List.of(
                    "SELECT 中文字段 AS 中文别名 FROM 中文表 ORDER BY 中文别名",
                    "WITH 中文统计 AS (SELECT COUNT(DISTINCT jjbh) AS 数量 FROM 表名) SELECT * FROM 中文统计",
                    "SELECT SUM(amount) OVER (PARTITION BY dept ORDER BY id) AS 累计 FROM t",
                    "SELECT * FROM t MATCH_RECOGNIZE (ORDER BY id MEASURES COUNT(*) AS n PATTERN (A+) DEFINE A AS id > 0)",
                    "SELECT LISTAGG(name, ',') WITHIN GROUP (ORDER BY name) FROM t",
                    "SELECT /*+ PARALLEL(4) */ * FROM t START WITH parent_id IS NULL CONNECT BY PRIOR id=parent_id",
                    "(SELECT 1 UNION ALL SELECT 2)",
                    "SELECT 'SELECT INTO backup; FOR UPDATE; DELETE FROM t' AS 描述 /* SELECT INTO; FOR UPDATE */")) {
                var config = proxy(type, null);
                assertEquals(sql, validator.validate(sql, config));
                assertEquals(sql, PgSchemaPolicy.apply(sql, config));
            }
        }
        assertDoesNotThrow(() -> validator.validate("SELECT INSERT('abcd',1,2,'x'), REPLACE('a','a','b')", proxy("mysql", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT TRUNCATE(amount,2) AS 金额 FROM t", proxy("mysql", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT set FROM t WHERE set='value'", proxy("postgresql", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT (delete) AS 原值 FROM t", proxy("postgresql", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT dept, amount FROM t MODEL DIMENSION BY (dept) "
                + "MEASURES (amount) RULES UPDATE (amount[ANY] = amount[CV()] * 2)", proxy("oracle", null)));
        assertDoesNotThrow(() -> validator.validate("WITH x(id) AS (SELECT 1 FROM DUAL) "
                + "SEARCH DEPTH FIRST BY id SET seq SELECT * FROM x", proxy("oracle", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT SQL_CALC_FOUND_ROWS * FROM t LIMIT 10", proxy("mysql", null)));
    }

    @Test
    void unicodeNamesRemainCompatibleWithRestrictedPgAndOracleScopes() {
        String sql = "WITH 统计 AS (SELECT SUBSTR(jjsj,1,8) AS 日期, COUNT(DISTINCT jjbh) AS 警情数 "
                + "FROM 警情表 WHERE cjxl='家庭暴力' GROUP BY SUBSTR(jjsj,1,8)) SELECT * FROM 统计 ORDER BY 日期";
        for (String type : List.of("postgresql", "oracle")) {
            var config = proxy(type, "a");
            String result = PgSchemaPolicy.apply(validator.validate(sql, config), config);
            assertTrue(result.contains("警情表"));
            assertTrue(result.contains("AS 日期"));
            assertTrue(result.contains("AS 警情数"));
            assertTrue(result.endsWith("FROM 统计 ORDER BY 日期"));
            assertFalse(result.contains("__pg_scope_"));
            assertTrue(result.contains(type.equals("oracle") ? "\"A\".警情表" : "\"a\".警情表"));
            assertThrows(SecurityException.class, () -> PgSchemaPolicy.apply(
                    validator.validate(sql.replace("FROM 警情表", "FROM b.警情表"), config), config));
            String quoted = "SELECT \"日期\" FROM \"警情表\"";
            assertTrue(PgSchemaPolicy.apply(validator.validate(quoted, config), config).contains("\"日期\""));
        }
        for (String reported : REPORTED_QUERIES) {
            String oracleSql = PgSchemaPolicy.apply(validator.validate(reported, proxy("oracle", "DIANXIN")), proxy("oracle", "DIANXIN"));
            String pgSql = PgSchemaPolicy.apply(validator.validate(reported, proxy("postgresql", "dianxin")), proxy("postgresql", "dianxin"));
            assertFalse(oracleSql.contains("__pg_scope_"));
            assertFalse(pgSql.contains("__pg_scope_"));
            assertTrue(oracleSql.contains("警情数"));
            assertTrue(pgSql.contains("警情数"));
        }
        var chineseScope = proxy("postgresql", "数据");
        assertTrue(PgSchemaPolicy.apply("SELECT * FROM 数据.警情表", chineseScope).contains("数据.警情表"));
        assertThrows(SecurityException.class, () -> PgSchemaPolicy.apply("SELECT * FROM 其他.警情表", chineseScope));
    }

    @Test
    void handlesDialectStringsAndIgnoresQuestionMarksInsideThem() {
        assertDoesNotThrow(() -> validator.validate("SELECT $$SELECT INTO; FOR UPDATE; ?$$ AS note", proxy("postgresql", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT $中文$DELETE; SELECT ?$中文$ AS note", proxy("postgresql", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT E'it\\'s; FOR UPDATE; ?' AS note", proxy("postgresql", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT q'[it's; SELECT INTO ?]' AS note FROM DUAL", proxy("oracle", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT q'[it's; SELECT INTO ?]' AS note", proxy("dm", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT 'it\\'s' AS note # DELETE FROM t\n", proxy("mysql", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT /*! SQL_NO_CACHE */ 1", proxy("mysql", null)));
        assertDoesNotThrow(() -> validator.validate("SELECT 1 /* outer /* inner DELETE */ SELECT INTO */", proxy("postgresql", null)));
    }

    @Test
    void stillBlocksWritesExportsLocksAndMultipleStatementsWithoutRegexFalsePositives() {
        for (String type : List.of("dm", "mysql", "postgresql", "oracle")) {
            for (String sql : List.of(
                    "DELETE FROM t", "INSERT INTO t VALUES(1)", "CREATE TABLE t(id int)", "CALL p()",
                    "SELECT 1; SELECT 2", "SELECT 1; DROP TABLE t", "SELECT 1;;",
                    "WITH x AS (DELETE FROM t RETURNING id) SELECT * FROM x",
                    "WITH x AS (SELECT 1) UPDATE t SET id=1",
                    "WITH x AS (SELECT 1), y AS (INSERT INTO t SELECT * FROM x RETURNING id) SELECT * FROM y",
                    "WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x",
                    "SELECT * FROM (WITH x AS (SELECT 1) DELETE FROM t RETURNING id) bad",
                    "SELECT * INTO backup FROM t", "SELECT INTO backup FROM t", "SELECT * FROM t INTO OUTFILE '/tmp/export'",
                    "SELECT * FROM t FOR /* comment */ UPDATE", "SELECT * FROM t FOR NO KEY UPDATE",
                    "SELECT * FROM t FOR KEY SHARE", "SELECT * FROM t LOCK IN SHARE MODE",
                    "SELECT 'unterminated", "SELECT (1", "SELECT 1 /* unclosed")) {
                assertThrows(IllegalArgumentException.class, () -> validator.validate(sql, proxy(type, null)), type + ": " + sql);
            }
            assertEquals("SELECT 1; -- final comment", validator.validate("SELECT 1; -- final comment", proxy(type, null)));
        }
        for (String sql : List.of("SELECT 1 /*!; DELETE FROM t */", "SELECT 1 /*M!; DELETE FROM t */",
                "SELECT 1--1; DELETE FROM t", "SELECT 'x\\'; DELETE FROM t;--'")) {
            assertThrows(IllegalArgumentException.class, () -> validator.validate(sql, proxy("mysql", null)), sql);
        }
        assertThrows(SecurityException.class, () -> PgSchemaPolicy.apply("SELECT * FROM t@remote", proxy("oracle", null)));
        assertThrows(IllegalArgumentException.class, () -> PgSchemaPolicy.apply(
                "SELECT * FROM t MATCH_RECOGNIZE (PATTERN(A+))", proxy("oracle", "HR")));
    }
}
