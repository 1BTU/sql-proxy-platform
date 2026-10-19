package com.example.dmdatabaseapi.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

/** 生成不可预测的数据库代理访问令牌。 */
@Service
public class TokenService {
    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "dbp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
