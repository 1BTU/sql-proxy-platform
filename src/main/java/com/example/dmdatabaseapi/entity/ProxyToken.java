package com.example.dmdatabaseapi.entity;

/** 一个数据库代理下可独立管理的访问令牌。 */
public record ProxyToken(
        long id,
        long proxyId,
        String remark,
        String tokenHash,
        String tokenCipher,
        String createdAt,
        String updatedAt
) {
}
