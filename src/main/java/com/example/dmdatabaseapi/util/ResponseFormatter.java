package com.example.dmdatabaseapi.util;

import com.example.dmdatabaseapi.entity.Pagination;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ResponseFormatter {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    public static String formatAsJson(List<Map<String, Object>> data, List<String> columns, Pagination pagination) {
        try {
            Map<String, Object> response = Map.of(
                    "code", 200,
                    "message", "success",
                    "data", data,
                    "columns", columns,
                    "pagination", pagination != null ? pagination : Map.of()
            );
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return "{\"code\":500,\"message\":\"格式化失败\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public static String formatAsMarkdown(List<Map<String, Object>> data, List<String> columns, Pagination pagination, String tableName) {
        StringBuilder md = new StringBuilder();
        md.append("| ").append(String.join(" | ", columns)).append(" |\n");
        md.append("| ").append(columns.stream().map(c -> "---").collect(Collectors.joining(" | "))).append(" |\n");

        if (data != null) {
            for (Map<String, Object> row : data) {
                md.append("| ");
                for (String col : columns) {
                    Object value = row.get(col);
                    md.append(value != null ? value.toString()
                            .replace("|", "\\|")
                            .replace("\r\n", "<br>")
                            .replace("\n", "<br>")
                            .replace("\r", "<br>") : "");
                    md.append(" | ");
                }
                md.append("\n");
            }
        }
        return md.toString();
    }

    public static String formatAsCsv(List<Map<String, Object>> data, List<String> columns) {
        if (data == null || data.isEmpty()) {
            return String.join(",", columns) + "\n（无数据）";
        }

        StringBuilder csv = new StringBuilder();

        csv.append(String.join(",", columns)).append("\n");

        for (Map<String, Object> row : data) {
            csv.append(row.values().stream()
                    .map(v -> {
                        if (v == null) return "";
                        String str = v.toString();
                        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
                            return "\"" + str.replace("\"", "\"\"") + "\"";
                        }
                        return str;
                    })
                    .collect(Collectors.joining(",")));
            csv.append("\n");
        }

        return csv.toString();
    }

    public static String formatAsHtml(List<Map<String, Object>> data, List<String> columns, Pagination pagination, String tableName) {
        if (data == null || data.isEmpty()) {
            return String.format("<h1>%s</h1><p>无数据</p>", tableName != null ? tableName : "查询结果");
        }

        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>\n<html><head><meta charset='UTF-8'><title>")
                .append(tableName != null ? tableName : "查询结果")
                .append("</title>")
                .append("<style>")
                .append("body{font-family:Arial,sans-serif;margin:20px;}")
                .append("table{border-collapse:collapse;width:100%;margin-top:20px;}")
                .append("th,td{border:1px solid #ddd;padding:8px;text-align:left;}")
                .append("th{background-color:#4CAF50;color:white;}")
                .append("tr:nth-child(even){background-color:#f2f2f2;}")
                .append(".pagination{margin-top:20px;}")
                .append("</style></head><body>");

        html.append("<h1>").append(tableName != null ? tableName : "查询结果").append("</h1>");

        html.append("<table><thead><tr>");
        for (String col : columns) {
            html.append("<th>").append(escapeHtml(col)).append("</th>");
        }
        html.append("</tr></thead><tbody>");

        for (Map<String, Object> row : data) {
            html.append("<tr>");
            for (String col : columns) {
                Object value = row.get(col);
                html.append("<td>").append(escapeHtml(value != null ? value.toString() : "")).append("</td>");
            }
            html.append("</tr>");
        }

        html.append("</tbody></table>");

        if (pagination != null) {
            html.append("<div class='pagination'>")
                    .append("<p>总数: ").append(data.size()).append(" 条 | ")
                    .append("第 ").append(pagination.getPage()).append(" 页/共 ").append(pagination.getPages()).append(" 页</p>")
                    .append("</div>");
        }

        html.append("</body></html>");

        return html.toString();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
