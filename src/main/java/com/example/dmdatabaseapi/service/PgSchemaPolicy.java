package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/** PG/Oracle 单 Schema 的直接表引用策略；间接访问权限由数据库负责。 */
public final class PgSchemaPolicy {
    private PgSchemaPolicy() { }

    public static boolean isPostgres(String type) {
        return "postgresql".equalsIgnoreCase(type) || "postgres".equalsIgnoreCase(type);
    }

    public static String configuredSchema(String type, String schema) {
        if (!isPostgres(type) || schema == null || schema.isBlank()) return null;
        String name = schema.trim();
        if (name.contains(",") || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("PG Schema 只能填写一个实际模式名，不能包含逗号或控制字符");
        }
        return name;
    }

    public static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    static String sqlForParsing(String sql) {
        return sqlForParsing(sql, false);
    }

    static String sqlForParsing(String sql, boolean oracle) {
        return new QuotedIdentifiers(sql, oracle).mask();
    }

    public static void checkConnection(Connection connection, ProxyConfig proxy) throws Exception {
        String schema = configuredSchema(proxy.dbType(), proxy.schema());
        if (schema == null) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.has_schema_privilege(current_user, oid, 'USAGE')
                FROM pg_catalog.pg_namespace WHERE nspname = ?
                """)) {
            statement.setString(1, schema);
            statement.setQueryTimeout(Math.min(proxy.queryTimeoutSeconds(), 30));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("配置的 PG Schema 不存在，请检查名称及大小写");
                if (!result.getBoolean(1)) throw new IllegalArgumentException("数据库账号没有配置的 PG Schema 的 USAGE 权限");
            }
        }
    }

    public static void setDefaultSchema(Connection connection, ProxyConfig proxy) throws Exception {
        String schema = configuredSchema(proxy.dbType(), proxy.schema());
        // PgConnection#setSchema quotes the supplied identifier itself (unlike currentSchema).
        if (schema != null) connection.setSchema(schema);
    }

    public static String apply(String sql, ProxyConfig proxy) {
        boolean oracle = OracleSupport.isOracle(proxy.dbType());
        String schema = oracle ? OracleSupport.configuredSchema(proxy.dbType(), proxy.schema())
                : configuredSchema(proxy.dbType(), proxy.schema());
        if (schema == null) {
            if (oracle) {
                for (var token : SqlLexicalScanner.scan(sql, "oracle", false)) {
                    if (token.symbol("@")) throw new SecurityException("不允许跨数据库或远程表引用");
                }
            }
            return sql;
        }
        try {
            QuotedIdentifiers identifiers = new QuotedIdentifiers(sql, oracle);
            Statement parsed = CCJSqlParserUtil.parse(identifiers.mask());
            if (!(parsed instanceof Select select)) throw unsupported();
            new Walker(schema, identifiers, oracle).select(select.getSelectBody(), select.getWithItemsList(), Set.of(), 0);
            return identifiers.restore(select.toString());
        } catch (SecurityException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw unsupported();
        }
    }

    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("该 SQL 无法完整校验 Schema 访问范围，请使用受支持的 SELECT 查询");
    }

    private static String identifier(String sqlIdentifier, boolean oracle) {
        if (sqlIdentifier == null) return "";
        if (sqlIdentifier.startsWith("\"") && sqlIdentifier.endsWith("\"")) {
            return sqlIdentifier.substring(1, sqlIdentifier.length() - 1).replace("\"\"", "\"");
        }
        return oracle ? sqlIdentifier.toUpperCase(Locale.ROOT) : sqlIdentifier.toLowerCase(Locale.ROOT);
    }

    private static final class Walker {
        private final String schema;
        private final QuotedIdentifiers identifiers;
        private final boolean oracle;
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());

        private Walker(String schema, QuotedIdentifiers identifiers, boolean oracle) {
            this.schema = schema;
            this.identifiers = identifiers;
            this.oracle = oracle;
        }

        private String name(String token) { return identifier(identifiers.restore(token), oracle); }

        private void select(SelectBody body, List<WithItem> withItems, Set<String> outer, int depth) throws Exception {
            if (depth > 128) throw unsupported();
            Set<String> visible = new HashSet<>(outer);
            if (withItems != null) {
                // WITH RECURSIVE permits self/forward references. Ordinary WITH is sequential:
                // a CTE's own body must still resolve a same-name base table normally.
                boolean recursive = withItems.stream().anyMatch(WithItem::isRecursive);
                if (recursive) withItems.forEach(item -> visible.add(name(item.getName())));
                for (WithItem item : withItems) {
                    // Oracle recursive factoring uses self-reference without WITH RECURSIVE.
                    if (oracle) visible.add(name(item.getName()));
                    if (item.getSubSelect() != null) walk(item.getSubSelect(), visible, depth + 1);
                    else if (item.isUseValues()) walk(item.getItemsList(), visible, depth + 1);
                    else throw unsupported();
                    visible.add(name(item.getName()));
                }
            }
            if (!(body instanceof PlainSelect) && !(body instanceof SetOperationList)) throw unsupported();
            fields(body, visible, depth + 1);
        }

        private void table(Table table, Set<String> visible) throws Exception {
            if (table.getNameParts().size() > 2 || table.getNameParts().stream().anyMatch(this::databaseLink)) {
                throw new SecurityException("不允许跨数据库或远程表引用");
            }
            // getName() strips @ suffixes in JSqlParser; raw name parts preserve quoted identifiers.
            String tableName = name(table.getNameParts().get(0));
            String prefix = table.getSchemaName();
            if (prefix == null || prefix.isBlank()) {
                if (!visible.contains(tableName)) {
                    if (oracle && tableName.equals("DUAL")) table.setSchemaName("SYS");
                    else if (schema != null) table.setSchemaName(quoteIdentifier(schema));
                }
            } else if (oracle && tableName.equals("DUAL") && name(prefix).equals("SYS")) {
                // Only SYS.DUAL is exempt; SYS's other objects remain restricted.
            } else if (schema == null) {
                // Unconfigured Oracle schemas follow the login user's object grants.
            } else if (!schema.equals(name(prefix))) {
                throw new SecurityException("当前代理仅允许直接查询配置的 Schema");
            }
            // Dialect-specific pivot expressions can also contain subqueries.
            walk(table.getPivot(), visible, 0);
            walk(table.getUnPivot(), visible, 0);
        }

        private boolean databaseLink(String part) {
            if (part == null) return false;
            part = identifiers.restore(part);
            boolean quoted = false;
            for (int i = 0; i < part.length(); i++) {
                char character = part.charAt(i);
                if (character == '"') {
                    if (quoted && i + 1 < part.length() && part.charAt(i + 1) == '"') i++;
                    else quoted = !quoted;
                } else if (character == '@' && !quoted) return true;
            }
            return false;
        }

        private void walk(Object node, Set<String> visible, int depth) throws Exception {
            if (node == null) return;
            if (depth > 128) throw unsupported();
            if (node instanceof Collection<?> nodes) {
                for (Object child : nodes) walk(child, visible, depth + 1);
                return;
            }
            if (node instanceof Map<?, ?> nodes) {
                for (Object child : nodes.values()) walk(child, visible, depth + 1);
                return;
            }
            // Column and qualified-star Table objects are names/aliases, not FROM relations.
            if (node instanceof Column || node instanceof AllTableColumns) return;
            if (node instanceof Table relation) { table(relation, visible); return; }
            if (node instanceof SubSelect sub) {
                select(sub.getSelectBody(), sub.getWithItemsList(), visible, depth + 1);
                walk(sub.getPivot(), visible, depth + 1);
                walk(sub.getUnPivot(), visible, depth + 1);
                return;
            }
            if (node instanceof Select select) {
                select(select.getSelectBody(), select.getWithItemsList(), visible, depth + 1);
                return;
            }
            if (node instanceof SelectBody body) {
                select(body, null, visible, depth + 1);
                return;
            }
            if (node instanceof Statement) throw unsupported();
            if (node instanceof FromItem && !(node instanceof SubJoin)
                    && !(node instanceof LateralSubSelect) && !(node instanceof ParenthesisFromItem)
                    && !(node instanceof ValuesList) && !(node instanceof TableFunction)) throw unsupported();
            if (!node.getClass().getName().startsWith("net.sf.jsqlparser.") || node.getClass().isEnum()) return;
            if (!active.add(node)) throw unsupported();
            try { fields(node, visible, depth + 1); }
            finally { active.remove(node); }
        }

        /**
         * Traverse every semantic field in pinned JSqlParser 4.6, including expression subqueries
         * in CASE, windows, ORDER BY, JOIN ON, HAVING, etc. Skip only parser token/back-reference
         * metadata; reflective access failure rejects the query rather than silently skipping it.
         */
        private void fields(Object node, Set<String> visible, int depth) throws Exception {
            for (Class<?> type = node.getClass(); type != null && type.getName().startsWith("net.sf.jsqlparser.");
                 type = type.getSuperclass()) {
                if (type.getName().startsWith("net.sf.jsqlparser.parser.")) break;
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || field.getType().isPrimitive()) continue;
                    if (field.getType().getName().startsWith("net.sf.jsqlparser.parser.")) continue;
                    if (!field.trySetAccessible()) throw unsupported();
                    walk(field.get(node), visible, depth + 1);
                }
            }
        }
    }

    /** JSqlParser 4.6 cannot tokenize unquoted Chinese names and splits doubled quotes;
     * mask those identifiers temporarily, without quoting or renaming the original SQL.
     * before parsing and restore them after rewriting. Strings/comments/dollar strings are
     * preserved byte-for-byte so text inside them cannot become a relation or a CTE name. */
    private static final class QuotedIdentifiers {
        private final String sql;
        private final Map<String, String> originals = new LinkedHashMap<>();
        private final Map<String, String> masks = new HashMap<>();
        private final String prefix;
        private final boolean oracle;

        private QuotedIdentifiers(String sql, boolean oracle) {
            this.sql = sql;
            this.oracle = oracle;
            String candidate;
            do { candidate = "__pg_scope_" + UUID.randomUUID().toString().replace("-", ""); }
            while (sql.contains(candidate));
            prefix = candidate;
        }

        private String mask() {
            StringBuilder output = new StringBuilder();
            int i = 0;
            while (i < sql.length()) {
                int start = i;
                if (sql.startsWith("--", i)) {
                    while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                } else if (sql.startsWith("/*", i)) {
                    int nesting = 1;
                    i += 2;
                    while (i < sql.length() && nesting > 0) {
                        if (sql.startsWith("/*", i)) { nesting++; i += 2; }
                        else if (sql.startsWith("*/", i)) { nesting--; i += 2; }
                        else i++;
                    }
                    if (nesting != 0) throw unsupported();
                } else if (oracle && alternativeQuoteStart(i) > 0) {
                    int prefixLength = alternativeQuoteStart(i);
                    int delimiterIndex = i + prefixLength;
                    if (delimiterIndex >= sql.length()) throw unsupported();
                    char opening = sql.charAt(delimiterIndex);
                    char closing = switch (opening) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> opening; };
                    int end = sql.indexOf("" + closing + '\'', delimiterIndex + 1);
                    if (end < 0) throw unsupported();
                    i = end + 2;
                } else if (sql.charAt(i) == '\'') {
                    boolean escape = i > 0 && (sql.charAt(i - 1) == 'e' || sql.charAt(i - 1) == 'E')
                            && (i < 2 || !Character.isJavaIdentifierPart(sql.charAt(i - 2)));
                    i++;
                    boolean closed = false;
                    while (i < sql.length()) {
                        if (escape && sql.charAt(i) == '\\') { i += 2; }
                        else if (sql.charAt(i) == '\'') {
                            i++;
                            if (i < sql.length() && sql.charAt(i) == '\'') i++;
                            else { closed = true; break; }
                        } else i++;
                    }
                    if (!closed || i > sql.length()) throw unsupported();
                } else if (!oracle && sql.charAt(i) == '$') {
                    var matcher = java.util.regex.Pattern.compile("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$").matcher(sql);
                    matcher.region(i, sql.length());
                    if (matcher.lookingAt()) {
                        String delimiter = matcher.group();
                        int end = sql.indexOf(delimiter, i + delimiter.length());
                        if (end < 0) throw unsupported();
                        i = end + delimiter.length();
                    } else i++;
                } else if (sql.charAt(i) == '"') {
                    i++;
                    boolean escaped = false, closed = false;
                    while (i < sql.length()) {
                        if (sql.charAt(i++) == '"') {
                            if (i < sql.length() && sql.charAt(i) == '"') { escaped = true; i++; }
                            else { closed = true; break; }
                        }
                    }
                    if (!closed) throw unsupported();
                    if (escaped || sql.substring(start, i).codePoints().anyMatch(point -> point > 127)) {
                        String original = sql.substring(start, i);
                        String token = masks.computeIfAbsent(original, value -> {
                            String masked = quoteIdentifier(prefix + masks.size() + "__");
                            originals.put(masked, value);
                            return masked;
                        });
                        output.append(token);
                        continue;
                    }
                } else if (oracle && sql.charAt(i) == '@') {
                    throw new SecurityException("不允许跨数据库或远程表引用");
                } else if (SqlLexicalScanner.identifierStart(sql.codePointAt(i))) {
                    i += Character.charCount(sql.codePointAt(i));
                    while (i < sql.length() && SqlLexicalScanner.identifierPart(sql.codePointAt(i))) {
                        i += Character.charCount(sql.codePointAt(i));
                    }
                    String original = sql.substring(start, i);
                    if (original.codePoints().anyMatch(point -> point > 127)) {
                        String token = masks.computeIfAbsent(original, value -> {
                            String masked = prefix + masks.size() + "__";
                            originals.put(masked, value);
                            return masked;
                        });
                        output.append(token);
                        continue;
                    }
                } else i++;
                output.append(sql, start, i);
            }
            return output.toString();
        }

        private int alternativeQuoteStart(int index) {
            if (index > 0 && Character.isJavaIdentifierPart(sql.charAt(index - 1))) return 0;
            if (sql.regionMatches(true, index, "q'", 0, 2)) return 2;
            if (sql.regionMatches(true, index, "nq'", 0, 3)) return 3;
            return 0;
        }

        private String restore(String value) {
            if (value == null) return null;
            for (var entry : originals.entrySet()) value = value.replace(entry.getKey(), entry.getValue());
            return value;
        }
    }
}
