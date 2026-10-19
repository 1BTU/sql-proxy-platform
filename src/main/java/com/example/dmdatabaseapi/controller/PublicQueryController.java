package com.example.dmdatabaseapi.controller;

import com.example.dmdatabaseapi.entity.ApiResponse;
import com.example.dmdatabaseapi.entity.ProxyConfig;
import com.example.dmdatabaseapi.entity.PublicQueryRequest;
import com.example.dmdatabaseapi.service.ProxyQueryService;
import com.example.dmdatabaseapi.service.ProxyService;
import com.example.dmdatabaseapi.util.ResponseFormatter;
import com.example.dmdatabaseapi.util.QueryErrorClassifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 使用 Bearer Token 定位数据源并执行受限只读查询。 */
@RestController
@RequestMapping("/api/v1/query")
public class PublicQueryController {
    private static final Logger log = LoggerFactory.getLogger(PublicQueryController.class);
    private final ProxyService proxyService;
    private final ProxyQueryService queryService;

    public PublicQueryController(ProxyService proxyService, ProxyQueryService queryService) {
        this.proxyService = proxyService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<?> query(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestBody PublicQueryRequest request) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "缺少 Bearer Token"));
        }

        ProxyConfig proxy;
        try {
            proxy = proxyService.findPublishedByToken(authorization.substring(7).trim());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "Token 无效或代理未发布"));
        }

        try {
            ProxyQueryService.QueryResult result = queryService.execute(proxy, request.sql());
            return format(result, request.format());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            String errorId = UUID.randomUUID().toString().substring(0, 8);
            log.error("Public query failed: errorId={}, proxyId={}, dbType={}",
                    errorId, proxy.id(), proxy.dbType(), e);
            QueryErrorClassifier.QueryError classified = QueryErrorClassifier.classify(e, errorId);
            return ResponseEntity.status(classified.status())
                    .body(ApiResponse.error(classified.status().value(), classified.message()));
        }
    }

    private ResponseEntity<?> format(ProxyQueryService.QueryResult result, String requestedFormat) {
        String format = requestedFormat == null || requestedFormat.isBlank() ? "json" : requestedFormat.toLowerCase();
        return switch (format) {
            case "markdown" -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .body(ResponseFormatter.formatAsMarkdown(result.rows(), result.columns(), null, null));
            case "csv" -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .header("Content-Disposition", "attachment; filename=query_result.csv")
                    .body(ResponseFormatter.formatAsCsv(result.rows(), result.columns()));
            case "html" -> ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .body(ResponseFormatter.formatAsHtml(result.rows(), result.columns(), null, null));
            case "json" -> ResponseEntity.ok(ApiResponse.success(result.rows(), result.columns()));
            default -> ResponseEntity.badRequest().body(ApiResponse.error(400, "format 仅支持 json、csv、markdown、html"));
        };
    }
}
