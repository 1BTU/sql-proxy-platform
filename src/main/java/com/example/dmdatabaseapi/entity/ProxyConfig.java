package com.example.dmdatabaseapi.entity;

/** 平台持久化的数据库代理配置。 */
public record ProxyConfig(
        long id,
        String name,
        String dbType,
        String host,
        int port,
        String database,
        String schema,
        String username,
        String passwordCipher,
        int maxRows,
        int queryTimeoutSeconds,
        String status,
        int configVersion,
        Integer testedVersion,
        String lastTestMessage,
        String lastTestAt,
        String tokenHash,
        String tokenCipher,
        String createdAt,
        String updatedAt,
        String oracleConnectionMode
) {
    public ProxyConfig(long id, String name, String dbType, String host, int port, String database,
                       String schema, String username, String passwordCipher, int maxRows, int queryTimeoutSeconds,
                       String status, int configVersion, Integer testedVersion, String lastTestMessage,
                       String lastTestAt, String tokenHash, String tokenCipher, String createdAt, String updatedAt) {
        this(id, name, dbType, host, port, database, schema, username, passwordCipher, maxRows, queryTimeoutSeconds,
                status, configVersion, testedVersion, lastTestMessage, lastTestAt, tokenHash, tokenCipher,
                createdAt, updatedAt, null);
    }
    public boolean isPublished() {
        return "PUBLISHED".equals(status);
    }
}
