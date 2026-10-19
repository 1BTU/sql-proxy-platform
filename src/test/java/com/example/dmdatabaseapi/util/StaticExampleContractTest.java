package com.example.dmdatabaseapi.util;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Check the actual packaged frontend, not just the TypeScript source. */
class StaticExampleContractTest {
    @Test
    void packagedCurlExampleContainsCompleteSqlAndJsonFormatWithoutParameters() throws Exception {
        String index = readResource("/static/index.html");
        var scripts = Pattern.compile("<script[^>]+src=\"([^\"]+)\"").matcher(index);
        String bundle = null;
        while (scripts.find()) {
            String content = readResource("/static" + scripts.group(1));
            if (content.contains("curl -X POST")) bundle = content;
        }
        assertNotNull(bundle, "Missing packaged curl example");
        int start = bundle.indexOf("curl -X POST");
        String example = bundle.substring(start, Math.min(start + 1000, bundle.length()));
        assertTrue(example.contains("SELECT 1"));
        assertTrue(example.contains("SELECT 1 AS RESULT FROM DUAL"));
        assertFalse(example.contains("params"), "Old frontend includes params");
        assertFalse(example.contains("WHERE id = ?"), "Old frontend includes a SQL placeholder");
        assertTrue(Pattern.compile("format\\s*:\\s*[\"']json[\"']").matcher(example).find(),
                "Curl example must explicitly include format json");
        for (String description : new String[]{"结构化 JSON", "逗号分隔文本",
                "标准 Markdown 表格", "HTML 表格页面", "format 可选，默认 json"}) {
            assertTrue(bundle.contains(description), "Missing output-format explanation: " + description);
        }
    }

    private String readResource(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, "Missing frontend resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
