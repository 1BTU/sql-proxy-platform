package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.entity.ProxyRequest;
import com.example.dmdatabaseapi.repository.ProxyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/oracle-configuration.db",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class OracleConfigurationIntegrationTest {
    @Autowired private ProxyService proxies;
    @Autowired private ProxyRepository repository;

    @Test
    void persistsModeAndPreservesTokensAcrossConnectionModeChanges() {
        String name = "Oracle " + UUID.randomUUID();
        var created = proxies.create(new ProxyRequest(name, "oracle", "db.local", 1521, "orcl", "HR",
                "reader", "secret", 1000, 30));
        assertEquals("SERVICE_NAME", created.oracleConnectionMode());
        repository.markTested(created.id(), 1, "test fixture");
        var first = proxies.publish(created.id());
        var second = proxies.createToken(created.id(), "report app");
        var updated = proxies.update(created.id(), new ProxyRequest(name, "oracle", "db.local", 1521, "orcl", "HR",
                "reader", "", 1000, 30, "SID"));
        assertEquals("SID", updated.oracleConnectionMode());
        assertEquals("DRAFT", updated.status());
        assertThrows(SecurityException.class, () -> proxies.findPublishedByToken(first.token()));
        repository.markTested(created.id(), updated.configVersion(), "test fixture");
        assertEquals(first.token(), proxies.publish(created.id()).token());
        assertDoesNotThrow(() -> proxies.findPublishedByToken(second.token()));
        assertEquals(2, proxies.listTokens(created.id()).size());
        assertThrows(IllegalArgumentException.class, () -> proxies.create(new ProxyRequest("invalid", "oracle", "db.local", 1521,
                "orcl", "HR", "reader", "secret", 1000, 30, "INVALID")));
    }
}
