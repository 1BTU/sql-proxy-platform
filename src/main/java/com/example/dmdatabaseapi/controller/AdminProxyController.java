package com.example.dmdatabaseapi.controller;

import com.example.dmdatabaseapi.entity.ApiResponse;
import com.example.dmdatabaseapi.entity.ProxyRequest;
import com.example.dmdatabaseapi.entity.ProxyView;
import com.example.dmdatabaseapi.service.ProxyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 管理端代理配置、测试、发布和令牌管理接口。 */
@RestController
@RequestMapping("/api/admin/proxies")
public class AdminProxyController {
    private final ProxyService proxyService;

    public AdminProxyController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @GetMapping
    public ApiResponse<List<ProxyView>> list() {
        return ApiResponse.success(proxyService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProxyView> get(@PathVariable long id) {
        return ApiResponse.success(proxyService.get(id));
    }

    @PostMapping
    public ApiResponse<ProxyView> create(@Valid @RequestBody ProxyRequest request) {
        return ApiResponse.success(proxyService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProxyView> update(@PathVariable long id, @Valid @RequestBody ProxyRequest request) {
        return ApiResponse.success(proxyService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable long id) {
        proxyService.delete(id);
        return ApiResponse.success("代理已删除");
    }

    @PostMapping("/{id}/test")
    public ApiResponse<ProxyView> test(@PathVariable long id) {
        return ApiResponse.success(proxyService.test(id));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<ProxyService.TokenResult> publish(@PathVariable long id) {
        return ApiResponse.success(proxyService.publish(id));
    }

    @PostMapping("/{id}/unpublish")
    public ApiResponse<ProxyView> unpublish(@PathVariable long id) {
        return ApiResponse.success(proxyService.unpublish(id));
    }

    @GetMapping("/{id}/token")
    public ApiResponse<ProxyService.TokenResult> token(@PathVariable long id) {
        return ApiResponse.success(proxyService.revealToken(id));
    }

    @PostMapping("/{id}/token/regenerate")
    public ApiResponse<ProxyService.TokenResult> regenerate(@PathVariable long id) {
        return ApiResponse.success(proxyService.regenerateToken(id));
    }

    @GetMapping("/{id}/tokens")
    public ApiResponse<List<ProxyService.TokenView>> tokens(@PathVariable long id) {
        return ApiResponse.success(proxyService.listTokens(id));
    }

    @PostMapping("/{id}/tokens")
    public ApiResponse<ProxyService.TokenResult> createToken(@PathVariable long id,
                                                              @RequestBody(required = false) TokenRequest request) {
        return ApiResponse.success(proxyService.createToken(id, request == null ? "" : request.remark()));
    }

    @GetMapping("/{id}/tokens/{tokenId}")
    public ApiResponse<ProxyService.TokenResult> revealToken(@PathVariable long id, @PathVariable long tokenId) {
        return ApiResponse.success(proxyService.revealToken(id, tokenId));
    }

    @PutMapping("/{id}/tokens/{tokenId}")
    public ApiResponse<ProxyService.TokenView> updateToken(@PathVariable long id, @PathVariable long tokenId,
                                                            @RequestBody TokenRequest request) {
        return ApiResponse.success(proxyService.updateTokenRemark(id, tokenId, request.remark()));
    }

    @PostMapping("/{id}/tokens/{tokenId}/regenerate")
    public ApiResponse<ProxyService.TokenResult> regenerateToken(@PathVariable long id, @PathVariable long tokenId) {
        return ApiResponse.success(proxyService.regenerateToken(id, tokenId));
    }

    @DeleteMapping("/{id}/tokens/{tokenId}")
    public ApiResponse<String> revokeToken(@PathVariable long id, @PathVariable long tokenId) {
        proxyService.revokeToken(id, tokenId);
        return ApiResponse.success("Token 已撤销");
    }

    public record TokenRequest(String remark) {
    }
}
