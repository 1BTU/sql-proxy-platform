package com.example.dmdatabaseapi.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Pagination {
    private Integer page;
    private Integer pageSize;
    private Long total;
    private Integer pages;
}