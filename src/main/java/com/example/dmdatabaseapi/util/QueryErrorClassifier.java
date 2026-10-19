package com.example.dmdatabaseapi.util;

import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.util.Locale;

/** 将底层数据库异常转换成不泄露连接信息的可操作错误提示。 */
public final class QueryErrorClassifier {
    private QueryErrorClassifier() {
    }

    public static QueryError classify(Throwable error, String errorId) {
        // Walk vendor exceptions first: pool wrappers can otherwise hide an ORA authentication
        // error behind a generic connection error. JDBC nextException is also significant.
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        var pending = new java.util.ArrayDeque<Throwable>();
        pending.add(error);
        while (!pending.isEmpty() && seen.size() < 256) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current.getCause() != null) pending.add(current.getCause());
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("orai18n.jar") && containsAny(message,
                    "non supported character set", "unsupported character set", "不支持的字符集", "不受支持的字符集")) {
                return oracleError(17056, errorId);
            }
            if (current instanceof SQLException sql) {
                if (sql.getNextException() != null) pending.add(sql.getNextException());
                QueryError oracle = oracleError(sql.getErrorCode(), errorId);
                if (oracle != null) return oracle;
            }
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);

            if (current instanceof SQLTimeoutException || containsAny(message,
                    "timeout", "timed out", "超时")) {
                return result(HttpStatus.GATEWAY_TIMEOUT, "查询执行超时，请缩小查询范围或联系管理员调整超时时间", errorId);
            }
            if (containsAny(message, "permission denied", "insufficient privilege", "not authorized",
                    "无权限", "权限不足", "没有权限")) {
                return result(HttpStatus.FORBIDDEN, "目标数据库账号无权访问所查询的对象", errorId);
            }

            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && state.startsWith("08")) {
                    return result(HttpStatus.SERVICE_UNAVAILABLE, "目标数据库连接中断或暂时不可用", errorId);
                }
                if (state != null && state.startsWith("28")) {
                    return result(HttpStatus.SERVICE_UNAVAILABLE, "目标数据库认证失败，请管理员检查代理账号配置", errorId);
                }
                if (current instanceof SQLSyntaxErrorException || (state != null && state.startsWith("42"))) {
                    return result(HttpStatus.BAD_REQUEST, "SQL 执行失败，请检查语法、表名、字段名及数据库方言", errorId);
                }
            }

            if (containsAny(message, "syntax error", "语法错误", "表或视图不存在", "对象不存在",
                    "relation does not exist", "unknown column", "column not found", "字段不存在", "列不存在")) {
                return result(HttpStatus.BAD_REQUEST, "SQL 执行失败，请检查语法、表名、字段名及数据库方言", errorId);
            }
            if (containsAny(message, "connection refused", "connection reset", "connection is closed",
                    "communications link failure", "broken pipe", "socket", "连接失败", "连接已关闭", "网络通信")) {
                return result(HttpStatus.SERVICE_UNAVAILABLE, "目标数据库连接中断或暂时不可用", errorId);
            }
        }
        return result(HttpStatus.INTERNAL_SERVER_ERROR, "查询执行失败", errorId);
    }

    private static QueryError oracleError(int code, String errorId) {
        return switch (code) {
            case 1031 -> result(HttpStatus.FORBIDDEN, "Oracle 账号权限不足，无法查询该对象", errorId);
            case 942 -> result(HttpStatus.BAD_REQUEST, "Oracle 表或视图不存在，或当前账号无权访问，请检查 Schema、对象名和授权", errorId);
            case 904 -> result(HttpStatus.BAD_REQUEST, "Oracle 字段名或标识符无效，请检查字段及大小写", errorId);
            case 900, 907, 911, 917, 923, 933, 936 -> result(HttpStatus.BAD_REQUEST,
                    "Oracle SQL 语法不兼容；11g 请使用 FROM DUAL、ROWNUM，不能使用 LIMIT", errorId);
            case 1017 -> result(HttpStatus.SERVICE_UNAVAILABLE, "Oracle 数据库账号或密码不正确，请检查代理配置", errorId);
            case 28000, 28001 -> result(HttpStatus.SERVICE_UNAVAILABLE, "Oracle 数据库账号已锁定或密码已过期", errorId);
            case 28040 -> result(HttpStatus.SERVICE_UNAVAILABLE, "Oracle 版本与 JDBC 认证协议不兼容，需要核对数据库版本和驱动", errorId);
            case 12505, 12514, 12154 -> result(HttpStatus.SERVICE_UNAVAILABLE, "Oracle 监听无法识别该服务，请检查连接模式与 Service Name/SID", errorId);
            case 12541, 17002 -> result(HttpStatus.SERVICE_UNAVAILABLE, "Oracle 连接或网络通信失败，请检查数据库地址、端口和监听状态", errorId);
            case 17056 -> result(HttpStatus.SERVICE_UNAVAILABLE,
                    "Oracle 数据库字符集缺少驱动扩展支持，请管理员升级到包含 orai18n 的平台镜像，无需修改数据库字符集", errorId);
            case 1013 -> result(HttpStatus.GATEWAY_TIMEOUT, "Oracle 查询超时或已取消，请缩小查询范围", errorId);
            default -> null;
        };
    }

    private static boolean containsAny(String message, String... fragments) {
        for (String fragment : fragments) {
            if (message.contains(fragment)) return true;
        }
        return false;
    }

    private static QueryError result(HttpStatus status, String message, String errorId) {
        return new QueryError(status, message + "（错误编号：" + errorId + "）");
    }

    public record QueryError(HttpStatus status, String message) {
    }
}
