package com.example.dmdatabaseapi.entity;

/** 返回给管理页面的脱敏代理信息。 */
public record ProxyView(
        long id,
        String name,
        String dbType,
        String host,
        int port,
        String database,
        String schema,
        String username,
        boolean hasPassword,
        int maxRows,
        int queryTimeoutSeconds,
        String status,
        int configVersion,
        Integer testedVersion,
        String lastTestMessage,
        String lastTestAt,
        int tokenCount,
        String createdAt,
        String updatedAt,
        String oracleConnectionMode
) {
    public static ProxyView from(ProxyConfig config, int tokenCount) {
        return new ProxyView(
                config.id(), config.name(), config.dbType(), config.host(), config.port(),
                config.database(), config.schema(), config.username(), true,
                config.maxRows(), config.queryTimeoutSeconds(), config.status(),
                config.configVersion(), config.testedVersion(), config.lastTestMessage(),
                config.lastTestAt(), tokenCount, config.createdAt(), config.updatedAt(), config.oracleConnectionMode());
    }
}
