package com.example.dmdatabaseapi.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResponseFormatterTest {

    @Test
    void markdownContainsOnlyTable() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", 1);
        row.put("NAME", "A|B\nC");

        String markdown = ResponseFormatter.formatAsMarkdown(
                List.of(row), List.of("ID", "NAME"), null, null);

        assertEquals("| ID | NAME |\n| --- | --- |\n| 1 | A\\|B<br>C | \n", markdown);
        assertFalse(markdown.contains("# 查询结果"));
        assertFalse(markdown.contains("总数"));
    }

    @Test
    void emptyMarkdownStillReturnsAValidTable() {
        assertEquals("| ID | NAME |\n| --- | --- |\n",
                ResponseFormatter.formatAsMarkdown(List.of(), List.of("ID", "NAME"), null, null));
    }
}
