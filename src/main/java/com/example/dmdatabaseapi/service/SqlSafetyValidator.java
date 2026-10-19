package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

/** Allow read-only query dialects without a generic parser grammar whitelist. */
@Service
public class SqlSafetyValidator {
    private static final Set<String> WRITES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE",
            "COMMIT", "ROLLBACK", "SAVEPOINT", "SET", "RESET", "USE", "COPY", "LOAD", "UNLOAD",
            "VACUUM", "ANALYZE", "REFRESH", "DECLARE", "BEGIN", "FUNCTION", "PROCEDURE");

    public String validate(String sql) {
        return validate(sql, null);
    }

    public String validate(String sql, ProxyConfig proxy) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }

        String cleaned = stripMarkdownFence(sql.trim());
        String type = proxy == null ? null : proxy.dbType();
        boolean alternate = (type == null || "mysql".equalsIgnoreCase(type) || PgSchemaPolicy.isPostgres(type))
                && cleaned.contains("\\");
        SqlLexicalScanner.MalformedSqlException malformed = null;
        boolean valid = false;
        // Check both escaping modes: a write must not hide behind session string settings.
        for (boolean escapes : alternate ? new boolean[]{false, true} : new boolean[]{false}) {
            try {
                check(SqlLexicalScanner.scan(cleaned, type, escapes));
                valid = true;
            } catch (SqlLexicalScanner.MalformedSqlException e) {
                // Even an unterminated alternative quoting mode must not hide an earlier
                // statement separator or write (e.g. a NO_BACKSLASH_ESCAPES session).
                for (var token : e.seenTokens()) {
                    if (token.symbol(";") || (token.kind() == SqlLexicalScanner.Kind.WORD
                            && WRITES.contains(token.text().toUpperCase(Locale.ROOT)))) {
                        throw new IllegalArgumentException("SQL 字符串转义存在歧义，可能包含多语句或写操作，请使用两个单引号转义");
                    }
                }
                malformed = e;
            }
        }
        if (!valid) throw malformed == null ? new IllegalArgumentException("SQL 不能为空") : malformed;
        return cleaned;
    }

    private void check(List<SqlLexicalScanner.Token> all) {
        if (all.isEmpty()) throw new IllegalArgumentException("SQL 不能为空");
        int end = all.size();
        if (all.get(end - 1).symbol(";")) end--;
        List<SqlLexicalScanner.Token> tokens = all.subList(0, end);
        if (tokens.isEmpty()) throw new IllegalArgumentException("SQL 不能为空");
        int head = 0;
        while (head < tokens.size() && tokens.get(head).symbol("(")) head++;
        if (head == tokens.size() || (!tokens.get(head).word("SELECT") && !tokens.get(head).word("WITH"))) {
            throw new IllegalArgumentException("仅允许 SELECT 或 WITH ... SELECT 查询");
        }
        int depth = 0;
        boolean mainSelect = tokens.get(head).word("SELECT");
        Map<Integer, Boolean> withQueries = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.symbol(";")) throw new IllegalArgumentException("仅允许执行一条只读 SQL");
            if (token.symbol("(")) depth++;
            if (token.symbol(")") && --depth < 0) throw new IllegalArgumentException("SQL 括号不匹配");
            if (token.symbol(")")) {
                int currentDepth = depth;
                withQueries.keySet().removeIf(level -> level > currentDepth);
            }
            if (token.word("SELECT") && depth == head) mainSelect = true;
            if (token.kind() != SqlLexicalScanner.Kind.WORD) continue;
            String word = token.text().toUpperCase(Locale.ROOT);
            boolean firstInParentheses = i > 0 && tokens.get(i - 1).symbol("(");
            if (token.word("WITH") && (i == head || firstInParentheses)) withQueries.put(depth, false);
            if (token.word("SELECT") && withQueries.containsKey(depth)) withQueries.put(depth, true);
            boolean explicitName = (i > 0 && (tokens.get(i - 1).symbol(".") || tokens.get(i - 1).word("AS")))
                    || (i + 1 < tokens.size() && tokens.get(i + 1).symbol("."));
            if (token.word("FOR") && (words(tokens, i, "FOR", "UPDATE") || words(tokens, i, "FOR", "SHARE")
                    || words(tokens, i, "FOR", "NO", "KEY", "UPDATE") || words(tokens, i, "FOR", "KEY", "SHARE"))) {
                throw new IllegalArgumentException("不允许加锁查询");
            }
            if (words(tokens, i, "LOCK", "IN", "SHARE", "MODE")) throw new IllegalArgumentException("不允许加锁查询");
            if (token.word("INTO") && !explicitName) throw new IllegalArgumentException("不允许 SELECT INTO 或文件导出");
            boolean pendingWithResult = Boolean.FALSE.equals(withQueries.get(depth));
            boolean cteName = pendingWithResult && i + 1 < tokens.size()
                    && (tokens.get(i + 1).word("AS") || (tokens.get(i + 1).symbol("(") && i > 0
                    && (tokens.get(i - 1).word("WITH") || tokens.get(i - 1).word("RECURSIVE") || tokens.get(i - 1).symbol(","))));
            boolean function = !pendingWithResult && i + 1 < tokens.size() && tokens.get(i + 1).symbol("(");
            boolean expressionName = !pendingWithResult && i + 1 < tokens.size()
                    && (tokens.get(i + 1).symbol(")") || tokens.get(i + 1).symbol(","));
            // A SELECT's MODEL UPDATE, scalar INSERT/REPLACE/TRUNCATE calls and column names
            // are not DML. Check statement heads (including writable CTEs), not every word.
            if (WRITES.contains(word) && (pendingWithResult || firstInParentheses)
                    && !explicitName && !cteName && !function && !expressionName
                    && !word.equals("SET")) {
                throw new IllegalArgumentException("只读查询不允许写入、DDL 或命令执行");
            }
        }
        if (depth != 0) throw new IllegalArgumentException("SQL 括号不匹配");
        if (!mainSelect) throw new IllegalArgumentException("WITH 必须以 SELECT 查询返回结果");
    }

    private boolean words(List<SqlLexicalScanner.Token> tokens, int index, String... words) {
        if (index + words.length > tokens.size()) return false;
        for (int i = 0; i < words.length; i++) if (!tokens.get(index + i).word(words[i])) return false;
        return true;
    }

    private String stripMarkdownFence(String sql) {
        if (sql.startsWith("```") && sql.endsWith("```")) {
            int newline = sql.indexOf('\n');
            if (newline >= 0) return sql.substring(newline + 1, sql.length() - 3).trim();
            if (sql.regionMatches(true, 0, "```sql", 0, 6)) return sql.substring(6, sql.length() - 3).trim();
            return sql.substring(3, sql.length() - 3).trim();
        }
        return sql;
    }
}
