package com.example.dmdatabaseapi.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SchemaInfo {
    private String username;
    private Integer userId;
    private String createTime;
}