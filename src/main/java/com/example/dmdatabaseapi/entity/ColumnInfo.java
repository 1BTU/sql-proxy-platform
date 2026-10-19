package com.example.dmdatabaseapi.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnInfo {
    private String columnName;
    private String dataType;
    private Integer dataLength;
    private String nullable;
    private Integer columnId;
}