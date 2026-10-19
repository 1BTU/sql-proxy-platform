package com.example.dmdatabaseapi.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Token boundaries only: dialect grammar is left to the target database. */
final class SqlLexicalScanner {
    enum Kind { WORD, IDENTIFIER, LITERAL, SYMBOL }
    record Token(Kind kind, String text, int start, int end) {
        boolean word(String value) { return kind == Kind.WORD && text.equalsIgnoreCase(value); }
        boolean symbol(String value) { return kind == Kind.SYMBOL && text.equals(value); }
    }
    static final class MalformedSqlException extends IllegalArgumentException {
        private final List<Token> seenTokens;
        MalformedSqlException(String message, List<Token> seenTokens) {
            super(message);
            this.seenTokens = List.copyOf(seenTokens);
        }
        List<Token> seenTokens() { return seenTokens; }
    }
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$(?:[\\p{L}_][\\p{L}\\p{N}_]*)?\\$");
    private final String sql;
    private final boolean mysql;
    private final boolean postgres;
    private final boolean oracle;
    private final boolean backslashEscapes;
    private final List<Token> tokens = new ArrayList<>();

    private SqlLexicalScanner(String sql, String type, boolean backslashEscapes) {
        this.sql = sql;
        String dbType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        mysql = dbType.equals("mysql");
        postgres = dbType.isEmpty() || PgSchemaPolicy.isPostgres(dbType);
        oracle = dbType.isEmpty() || OracleSupport.isOracle(dbType) || dbType.equals("dm") || dbType.equals("dameng");
        this.backslashEscapes = backslashEscapes;
    }
    static List<Token> scan(String sql, String type, boolean backslashEscapes) {
        var scanner = new SqlLexicalScanner(sql, type, backslashEscapes);
        scanner.segment(0, sql.length(), 0);
        return scanner.tokens;
    }
    private void segment(int begin, int end, int nesting) {
        if (nesting > 32) throw malformed("SQL 注释嵌套过深");
        int i = begin;
        while (i < end) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '\uFEFF') { i++; continue; }
            if (c == '\0') throw malformed("SQL 包含无效字符");
            if (sql.startsWith("--", i) && (!mysql || i + 2 == end
                    || Character.isWhitespace(sql.charAt(i + 2)) || Character.isISOControl(sql.charAt(i + 2)))) {
                i += 2;
                while (i < end && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (mysql && c == '#') {
                while (i < end && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (sql.startsWith("/*", i)) {
                int start = i;
                boolean executable = mysql && (sql.startsWith("/*!", i)
                        || sql.regionMatches(true, i, "/*M!", 0, 4));
                i += 2;
                int depth = 1;
                while (i < end && depth != 0) {
                    if (postgres && sql.startsWith("/*", i)) { depth++; i += 2; }
                    else if (sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth != 0) throw malformed("SQL 注释未闭合");
                if (executable) {
                    int body = start + (sql.charAt(start + 2) == '!' ? 3 : 4);
                    while (body < i - 2 && Character.isDigit(sql.charAt(body))) body++;
                    // Version comments execute SQL; never hide writes inside them.
                    segment(body, i - 2, nesting + 1);
                }
                continue;
            }
            int start = i;
            int alternative = oracle ? alternativeQuoteStart(i) : 0;
            if (alternative > 0) {
                int delimiter = i + alternative;
                if (delimiter >= end) throw malformed("SQL 字符串未闭合");
                char open = sql.charAt(delimiter);
                char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
                int finish = sql.indexOf("" + close + '\'', delimiter + 1);
                if (finish < 0 || finish + 2 > end) throw malformed("SQL 字符串未闭合");
                i = finish + 2;
                add(Kind.LITERAL, start, i);
                continue;
            }
            if (postgres && c == '$') {
                var match = DOLLAR_QUOTE.matcher(sql).region(i, end);
                if (match.lookingAt()) {
                    String delimiter = match.group();
                    int finish = sql.indexOf(delimiter, i + delimiter.length());
                    if (finish < 0 || finish + delimiter.length() > end) throw malformed("SQL 字符串未闭合");
                    i = finish + delimiter.length();
                    add(Kind.LITERAL, start, i);
                    continue;
                }
            }
            boolean escapeString = postgres && (c == 'e' || c == 'E') && i + 1 < end
                    && sql.charAt(i + 1) == '\'' && (i == 0 || !identifierPart(sql.codePointBefore(i)));
            if (escapeString || c == '\'' || c == '"' || c == '`') {
                if (escapeString) i++;
                char quote = sql.charAt(i++);
                boolean escapes = escapeString || (backslashEscapes && (quote == '\'' || mysql));
                boolean closed = false;
                while (i < end) {
                    char value = sql.charAt(i++);
                    if (escapes && value == '\\') {
                        if (i >= end) throw malformed("SQL 字符串未闭合");
                        i++;
                    } else if (value == quote) {
                        if (i < end && sql.charAt(i) == quote) i++;
                        else { closed = true; break; }
                    }
                }
                if (!closed) throw malformed("SQL 字符串或标识符未闭合");
                add(quote == '\'' || (mysql && quote == '"') ? Kind.LITERAL : Kind.IDENTIFIER, start, i);
                continue;
            }
            int point = sql.codePointAt(i);
            if (identifierStart(point)) {
                i += Character.charCount(point);
                while (i < end && identifierPart(sql.codePointAt(i))) i += Character.charCount(sql.codePointAt(i));
                add(Kind.WORD, start, i);
            } else {
                i += Character.charCount(point);
                add(Kind.SYMBOL, start, i);
            }
        }
    }
    private int alternativeQuoteStart(int position) {
        if (position > 0 && identifierPart(sql.codePointBefore(position))) return 0;
        if (sql.regionMatches(true, position, "q'", 0, 2)) return 2;
        if (sql.regionMatches(true, position, "nq'", 0, 3)) return 3;
        return 0;
    }
    static boolean identifierStart(int point) { return Character.isUnicodeIdentifierStart(point) || point == '_' || point == '$'; }
    static boolean identifierPart(int point) { return Character.isUnicodeIdentifierPart(point) || point == '$' || point == '#'; }
    private void add(Kind kind, int start, int end) { tokens.add(new Token(kind, sql.substring(start, end), start, end)); }
    private MalformedSqlException malformed(String message) { return new MalformedSqlException(message, tokens); }
}
