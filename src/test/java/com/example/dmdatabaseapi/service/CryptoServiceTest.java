package com.example.dmdatabaseapi.service;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CryptoServiceTest {
    @Test
    void encryptsAndRejectsTampering() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        CryptoService service = new CryptoService(Base64.getEncoder().encodeToString(raw));

        String cipher = service.encrypt("database-password");
        assertNotEquals("database-password", cipher);
        assertEquals("database-password", service.decrypt(cipher));

        byte[] changed = Base64.getDecoder().decode(cipher);
        changed[changed.length - 1] ^= 1;
        assertThrows(IllegalStateException.class,
                () -> service.decrypt(Base64.getEncoder().encodeToString(changed)));
    }

    @Test
    void requiresExactlyThirtyTwoBytes() {
        assertThrows(IllegalStateException.class,
                () -> new CryptoService(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
