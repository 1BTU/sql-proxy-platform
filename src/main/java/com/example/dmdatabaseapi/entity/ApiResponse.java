package com.example.dmdatabaseapi.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private Integer code;
    private String message;
    private T data;
    private List<String> columns;
    private Pagination pagination;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data, null, null);
    }

    public static <T> ApiResponse<T> success(T data, List<String> columns) {
        return new ApiResponse<>(200, "success", data, columns, null);
    }

    public static <T> ApiResponse<T> success(T data, List<String> columns, Pagination pagination) {
        return new ApiResponse<>(200, "success", data, columns, pagination);
    }

    @SuppressWarnings("unchecked")
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return (ApiResponse<T>) new ApiResponse<>(code, message, null, null, null);
    }

    @SuppressWarnings("unchecked")
    public static <T> ApiResponse<T> error(Integer code, String message, String errorDetail) {
        return (ApiResponse<T>) new ApiResponse<>(code, message, errorDetail, null, null);
    }
}