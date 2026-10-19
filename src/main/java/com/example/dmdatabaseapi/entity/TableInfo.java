package com.example.dmdatabaseapi.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableInfo {
    private String tableName;
    private String tableType;
    private String createTime;
}