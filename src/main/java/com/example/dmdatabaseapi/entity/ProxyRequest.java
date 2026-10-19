package com.example.dmdatabaseapi.entity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 管理端新增或编辑代理时提交的字段。 */
public record ProxyRequest(
        @NotBlank(message = "代理名称不能为空") String name,
        @NotBlank(message = "数据库类型不能为空") String dbType,
        @NotBlank(message = "主机地址不能为空") String host,
        @NotNull(message = "端口不能为空") @Min(1) @Max(65535) Integer port,
        @NotBlank(message = "数据库名称不能为空") String database,
        String schema,
        @NotBlank(message = "数据库用户名不能为空") String username,
        String password,
        @NotNull @Min(1) @Max(100000) Integer maxRows,
        @NotNull @Min(1) @Max(600) Integer queryTimeoutSeconds,
        String oracleConnectionMode
) {
    public ProxyRequest(String name, String dbType, String host, Integer port, String database, String schema,
                        String username, String password, Integer maxRows, Integer queryTimeoutSeconds) {
        this(name, dbType, host, port, database, schema, username, password, maxRows, queryTimeoutSeconds, null);
    }
}
