package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyConfig;
import com.example.dmdatabaseapi.entity.ProxyRequest;
import com.example.dmdatabaseapi.entity.ProxyToken;
import com.example.dmdatabaseapi.entity.ProxyView;
import com.example.dmdatabaseapi.repository.ProxyRepository;
import com.example.dmdatabaseapi.repository.ProxyTokenRepository;
import com.example.dmdatabaseapi.util.QueryErrorClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/** 编排代理的创建、连接测试、发布、撤销与令牌轮换。 */
@Service
public class ProxyService {
    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);
    private final ProxyRepository repository;
    private final ProxyTokenRepository tokenRepository;
    private final CryptoService cryptoService;
    private final TokenService tokenService;
    private final TargetDataSourceManager dataSourceManager;

    public ProxyService(ProxyRepository repository, ProxyTokenRepository tokenRepository,
                        CryptoService cryptoService, TokenService tokenService,
                        TargetDataSourceManager dataSourceManager) {
        this.repository = repository;
        this.tokenRepository = tokenRepository;
        this.cryptoService = cryptoService;
        this.tokenService = tokenService;
        this.dataSourceManager = dataSourceManager;
    }

    public List<ProxyView> list() {
        return repository.findAll().stream().map(this::view).toList();
    }

    public ProxyView get(long id) {
        return view(require(id));
    }

    public ProxyView create(ProxyRequest request) {
        validateType(request.dbType());
        PgSchemaPolicy.configuredSchema(request.dbType(), request.schema());
        OracleSupport.validate(request);
        if (request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("新建代理时数据库密码不能为空");
        }
        try {
            long id = repository.create(request, cryptoService.encrypt(request.password()));
            return get(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("代理名称已存在");
        }
    }

    public ProxyView update(long id, ProxyRequest request) {
        validateType(request.dbType());
        PgSchemaPolicy.configuredSchema(request.dbType(), request.schema());
        OracleSupport.validate(request);
        ProxyConfig existing = require(id);
        String passwordCipher = request.password() == null || request.password().isBlank()
                ? existing.passwordCipher() : cryptoService.encrypt(request.password());
        try {
            repository.update(id, request, passwordCipher);
            dataSourceManager.evict(id);
            return get(id);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("代理名称已存在");
        }
    }

    public ProxyView test(long id) {
        ProxyConfig proxy = require(id);
        try (HikariDataSource dataSource = dataSourceManager.createForTest(
                proxy, cryptoService.decrypt(proxy.passwordCipher()));
             Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.min(proxy.queryTimeoutSeconds(), 30));
            PgSchemaPolicy.checkConnection(connection, proxy);
            OracleSupport.checkSchema(connection, proxy);
            OracleSupport.setDefaultSchema(connection, proxy);
            try (OracleSupport.ReadOnlyTransaction transaction = OracleSupport.isOracle(proxy.dbType())
                    ? OracleSupport.beginReadOnly(connection, Math.min(proxy.queryTimeoutSeconds(), 30)) : null) {
                statement.execute(OracleSupport.testSql(proxy.dbType()));
            }
            repository.markTested(id, proxy.configVersion(), "连接成功");
            return get(id);
        } catch (IllegalArgumentException e) {
            repository.markTestFailed(id, e.getMessage());
            throw e;
        } catch (Exception e) {
            if (OracleSupport.isOracle(proxy.dbType())) {
                String errorId = UUID.randomUUID().toString().substring(0, 8);
                log.error("Oracle connection test failed: errorId={}, proxyId={}", errorId, id, e);
                String message = QueryErrorClassifier.classify(e, errorId).message();
                repository.markTestFailed(id, message);
                throw new IllegalArgumentException(message);
            }
            repository.markTestFailed(id, "连接失败");
            throw new IllegalArgumentException("连接失败，请检查地址、端口、数据库和账号信息");
        }
    }

    public TokenResult publish(long id) {
        ProxyConfig proxy = require(id);
        if (proxy.testedVersion() == null || proxy.testedVersion() != proxy.configVersion()) {
            throw new IllegalArgumentException("当前配置尚未测试成功，不能发布");
        }
        ProxyToken existing = tokenRepository.findFirst(id).orElse(null);
        if (existing == null) {
            String token = tokenService.generate();
            existing = tokenRepository.create(id, "默认 Token", cryptoService.sha256(token), cryptoService.encrypt(token));
            repository.publish(id, null, null);
            return tokenResult(existing, token);
        }
        repository.publish(id, null, null);
        return tokenResult(existing, cryptoService.decrypt(existing.tokenCipher()));
    }

    public ProxyView unpublish(long id) {
        ProxyConfig proxy = require(id);
        if (!proxy.isPublished()) {
            throw new IllegalArgumentException("代理尚未发布");
        }
        repository.unpublish(id);
        dataSourceManager.evict(id);
        return get(id);
    }

    public TokenResult revealToken(long id) {
        require(id);
        ProxyToken token = tokenRepository.findFirst(id)
                .orElseThrow(() -> new IllegalArgumentException("代理尚未生成 Token"));
        return tokenResult(token, cryptoService.decrypt(token.tokenCipher()));
    }

    public TokenResult regenerateToken(long id) {
        require(id);
        ProxyToken first = tokenRepository.findFirst(id)
                .orElseThrow(() -> new IllegalArgumentException("代理尚未生成 Token"));
        return regenerateToken(id, first.id());
    }

    public List<TokenView> listTokens(long id) {
        require(id);
        return tokenRepository.findByProxyId(id).stream().map(TokenView::from).toList();
    }

    public TokenResult createToken(long id, String remark) {
        ProxyConfig proxy = require(id);
        if (!proxy.isPublished()) throw new IllegalArgumentException("只有已发布代理可以新增 Token");
        String value = tokenService.generate();
        ProxyToken token = tokenRepository.create(id, normalizeRemark(remark),
                cryptoService.sha256(value), cryptoService.encrypt(value));
        return tokenResult(token, value);
    }

    public TokenResult revealToken(long id, long tokenId) {
        require(id);
        ProxyToken token = requireToken(id, tokenId);
        return tokenResult(token, cryptoService.decrypt(token.tokenCipher()));
    }

    public TokenView updateTokenRemark(long id, long tokenId, String remark) {
        require(id);
        requireToken(id, tokenId);
        tokenRepository.updateRemark(id, tokenId, normalizeRemark(remark));
        return TokenView.from(requireToken(id, tokenId));
    }

    public TokenResult regenerateToken(long id, long tokenId) {
        ProxyConfig proxy = require(id);
        if (!proxy.isPublished()) throw new IllegalArgumentException("只有已发布代理可以重新生成 Token");
        ProxyToken token = requireToken(id, tokenId);
        String value = tokenService.generate();
        tokenRepository.regenerate(id, tokenId, cryptoService.sha256(value), cryptoService.encrypt(value));
        return tokenResult(requireToken(id, tokenId), value);
    }

    public void revokeToken(long id, long tokenId) {
        require(id);
        requireToken(id, tokenId);
        tokenRepository.delete(id, tokenId);
    }

    public void delete(long id) {
        require(id);
        dataSourceManager.evict(id);
        tokenRepository.deleteByProxyId(id);
        repository.delete(id);
    }

    public ProxyConfig findPublishedByToken(String token) {
        if (token == null || !token.startsWith("dbp_")) {
            throw new SecurityException("无效 Token");
        }
        return repository.findPublishedByTokenHash(cryptoService.sha256(token))
                .orElseThrow(() -> new SecurityException("Token 无效或代理未发布"));
    }

    public String decryptPassword(ProxyConfig proxy) {
        return cryptoService.decrypt(proxy.passwordCipher());
    }

    private ProxyConfig require(long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("代理不存在"));
    }

    private ProxyToken requireToken(long proxyId, long tokenId) {
        return tokenRepository.findById(proxyId, tokenId)
                .orElseThrow(() -> new IllegalArgumentException("Token 不存在"));
    }

    private ProxyView view(ProxyConfig proxy) {
        return ProxyView.from(proxy, tokenRepository.count(proxy.id()));
    }

    private TokenResult tokenResult(ProxyToken token, String value) {
        return new TokenResult(token.id(), token.remark(), value, get(token.proxyId()));
    }

    private String normalizeRemark(String remark) {
        String value = remark == null ? "" : remark.trim();
        if (value.length() > 200) throw new IllegalArgumentException("Token 备注不能超过 200 个字符");
        return value;
    }

    private void validateType(String type) {
        if (type == null || !(type.equalsIgnoreCase("mysql") || type.equalsIgnoreCase("postgresql")
                || type.equalsIgnoreCase("postgres") || type.equalsIgnoreCase("dm")
                || type.equalsIgnoreCase("dameng") || type.equalsIgnoreCase("oracle"))) {
            throw new IllegalArgumentException("数据库类型仅支持 mysql、postgresql、dm、oracle");
        }
    }

    public record TokenView(long id, long proxyId, String remark, String createdAt, String updatedAt) {
        public static TokenView from(ProxyToken token) {
            return new TokenView(token.id(), token.proxyId(), token.remark(), token.createdAt(), token.updatedAt());
        }
    }

    public record TokenResult(long id, String remark, String token, ProxyView proxy) {
    }
}
