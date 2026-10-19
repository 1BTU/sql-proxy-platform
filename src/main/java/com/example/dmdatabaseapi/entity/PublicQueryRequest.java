package com.example.dmdatabaseapi.entity;

/** 外部调用方提交的查询，不包含任何数据库连接信息。 */
public record PublicQueryRequest(String sql, String format) {
}
