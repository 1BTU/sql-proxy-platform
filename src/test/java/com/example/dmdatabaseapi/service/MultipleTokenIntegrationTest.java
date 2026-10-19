package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyRequest;
import com.example.dmdatabaseapi.entity.ProxyView;
import com.example.dmdatabaseapi.repository.ProxyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/multiple-token-integration.db",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "app.admin.username=admin",
        "app.admin.password=Admin@123456"
})
class MultipleTokenIntegrationTest {
    @Autowired
    private ProxyService proxyService;

    @Autowired
    private ProxyRepository proxyRepository;

    @Test
    void supportsMultipleIndependentTokensWithRemarks() {
        ProxyView proxy = proxyService.create(new ProxyRequest(
                "多令牌测试代理", "dm", "127.0.0.1", 5236, "TEST", null,
                "readonly", "secret", 1000, 30));
        proxyRepository.markTested(proxy.id(), proxy.configVersion(), "连接成功");

        ProxyService.TokenResult first = proxyService.publish(proxy.id());
        ProxyService.TokenResult second = proxyService.createToken(proxy.id(), "报表系统");

        proxyRepository.markTested(proxy.id(), proxy.configVersion(), "再次测试成功");
        assertEquals("PUBLISHED", proxyService.get(proxy.id()).status());

        assertNotEquals(first.token(), second.token());
        assertEquals(2, proxyService.listTokens(proxy.id()).size());
        assertEquals("报表系统", proxyService.updateTokenRemark(proxy.id(), second.id(), "报表系统").remark());
        assertDoesNotThrow(() -> proxyService.findPublishedByToken(first.token()));
        assertDoesNotThrow(() -> proxyService.findPublishedByToken(second.token()));

        proxyService.revokeToken(proxy.id(), first.id());
        assertThrows(SecurityException.class, () -> proxyService.findPublishedByToken(first.token()));
        assertDoesNotThrow(() -> proxyService.findPublishedByToken(second.token()));
    }
}
