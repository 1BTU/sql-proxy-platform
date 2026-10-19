package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLXML;
import java.sql.Struct;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在受限连接上执行只读查询并限制时间和返回行数。 */
@Service
public class ProxyQueryService {
    private static final Logger log = LoggerFactory.getLogger(ProxyQueryService.class);
    private static final int MAX_LOB_SIZE = 1_000_000;
    private final SqlSafetyValidator sqlSafetyValidator;
    private final TargetDataSourceManager dataSourceManager;
    private final ProxyService proxyService;

    public ProxyQueryService(SqlSafetyValidator sqlSafetyValidator,
                             TargetDataSourceManager dataSourceManager,
                             ProxyService proxyService) {
        this.sqlSafetyValidator = sqlSafetyValidator;
        this.dataSourceManager = dataSourceManager;
        this.proxyService = proxyService;
    }

    public QueryResult execute(ProxyConfig proxy, String sql) throws Exception {
        String safeSql = PgSchemaPolicy.apply(sqlSafetyValidator.validate(sql, proxy), proxy);
        DataSource dataSource = dataSourceManager.get(proxy, proxyService.decryptPassword(proxy));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            // 每次借用连接时恢复默认 Schema，避免上一次查询修改会话搜索路径。
            PgSchemaPolicy.setDefaultSchema(connection, proxy);
            OracleSupport.setDefaultSchema(connection, proxy);
            if (!OracleSupport.isOracle(proxy.dbType())) setReadOnlyCompatible(connection, proxy);
            try (OracleSupport.ReadOnlyTransaction transaction = OracleSupport.isOracle(proxy.dbType())
                    ? OracleSupport.beginReadOnly(connection, proxy.queryTimeoutSeconds()) : null;
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(proxy.queryTimeoutSeconds());
                statement.setMaxRows(proxy.maxRows());
                // The API accepts complete SQL, not a parameterized template. A Statement
                // preserves native PG ?, ?| and ?& JSON operators without JDBC rewriting.
                try (ResultSet resultSet = statement.executeQuery(safeSql)) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        columns.add(metadata.getColumnLabel(i));
                    }
                    while (resultSet.next() && rows.size() < proxy.maxRows()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            row.put(columns.get(i - 1), readJdbcValue(resultSet, i));
                        }
                        rows.add(row);
                    }
                }
            }
        }
        return new QueryResult(rows, columns);
    }

    private Object readJdbcValue(ResultSet resultSet, int columnIndex) throws Exception {
        Object value;
        try {
            value = resultSet.getObject(columnIndex);
        } catch (java.sql.SQLException unsupportedObjectType) {
            // 某些达梦专有类型不支持 getObject，但可以安全读取其文本表示。
            return resultSet.getString(columnIndex);
        }
        if (value instanceof oracle.sql.TIMESTAMPTZ timestamp) {
            return timestamp.offsetDateTimeValue(resultSet.getStatement().getConnection()
                    .unwrap(oracle.jdbc.OracleConnection.class)).toString();
        }
        if (value instanceof oracle.sql.TIMESTAMPLTZ timestamp) {
            return timestamp.offsetDateTimeValue(resultSet.getStatement().getConnection()
                    .unwrap(oracle.jdbc.OracleConnection.class)).toString();
        }
        return normalizeJdbcValue(value);
    }

    /** 在 ResultSet/Connection 关闭前，将依赖连接的 JDBC 对象转换为可序列化值。 */
    static Object normalizeJdbcValue(Object value) throws Exception {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof oracle.sql.NUMBER number) return number.bigDecimalValue();
        if (value instanceof oracle.sql.TIMESTAMP timestamp) return timestamp.timestampValue().toLocalDateTime().toString();
        if (value instanceof oracle.sql.DATE date) return date.timestampValue().toLocalDateTime().toString();
        if (value instanceof oracle.sql.ROWID rowId) return rowId.stringValue();
        if (value instanceof Character character) return character.toString();
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                return readCharacters(reader);
            } finally {
                try { clob.free(); } catch (Exception ignored) { }
            }
        }
        if (value instanceof Blob blob) {
            try (InputStream input = blob.getBinaryStream()) {
                return Base64.getEncoder().encodeToString(readBytes(input));
            } finally {
                try { blob.free(); } catch (Exception ignored) { }
            }
        }
        if (value instanceof SQLXML xml) {
            try {
                return xml.getString();
            } finally {
                try { xml.free(); } catch (Exception ignored) { }
            }
        }
        if (value instanceof java.sql.Array sqlArray) {
            try {
                return normalizeArray(sqlArray.getArray());
            } finally {
                try { sqlArray.free(); } catch (Exception ignored) { }
            }
        }
        if (value instanceof Struct struct) {
            return normalizeArray(struct.getAttributes());
        }
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.sql.Time time) return time.toLocalTime().toString();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof java.util.Date date) return date.toInstant().toString();
        if (value instanceof TemporalAccessor) return value.toString();

        // 达梦专有时间、区间、ROWID、空间等驱动对象不能交给 Jackson 反射序列化。
        return value.toString();
    }

    private static List<Object> normalizeArray(Object array) throws Exception {
        if (array == null) return List.of();
        int length = Array.getLength(array);
        List<Object> values = new ArrayList<>(length);
        for (int i = 0; i < length; i++) values.add(normalizeJdbcValue(Array.get(array, i)));
        return values;
    }

    private static String readCharacters(Reader reader) throws Exception {
        StringBuilder value = new StringBuilder();
        char[] buffer = new char[8192];
        int remaining = MAX_LOB_SIZE;
        while (remaining > 0) {
            int count = reader.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) return value.toString();
            value.append(buffer, 0, count);
            remaining -= count;
        }
        return value.append("...[内容已截断]").toString();
    }

    private static byte[] readBytes(InputStream input) throws Exception {
        byte[] output = new byte[MAX_LOB_SIZE];
        int offset = 0;
        while (offset < output.length) {
            int count = input.read(output, offset, output.length - offset);
            if (count < 0) break;
            offset += count;
        }
        return java.util.Arrays.copyOf(output, offset);
    }

    private void setReadOnlyCompatible(Connection connection, ProxyConfig proxy) throws Exception {
        try {
            connection.setReadOnly(true);
        } catch (java.sql.SQLException e) {
            if ("dm".equalsIgnoreCase(proxy.dbType()) || "dameng".equalsIgnoreCase(proxy.dbType())) {
                // 部分达梦 JDBC 版本会在此抛出“不支持的接口”。SQL 解析器和数据库只读账号仍是安全边界。
                log.warn("DM JDBC driver does not support setting read-only mode for proxy {}: {}",
                        proxy.id(), e.getMessage());
                return;
            }
            throw e;
        }
    }

    public record QueryResult(List<Map<String, Object>> rows, List<String> columns) {
    }
}
