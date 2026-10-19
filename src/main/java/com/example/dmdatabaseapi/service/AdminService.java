package com.example.dmdatabaseapi.service;

import com.example.dmdatabaseapi.repository.AdminRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 初始化并验证平台管理员，负责首次强制改密。 */
@Service
public class AdminService {
    private final AdminRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final String initialUsername;
    private final String initialPassword;

    public AdminService(
            AdminRepository repository,
            PasswordEncoder passwordEncoder,
            @Value("${app.admin.username}") String initialUsername,
            @Value("${app.admin.password}") String initialPassword) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.initialUsername = initialUsername;
        this.initialPassword = initialPassword;
    }

    @PostConstruct
    public void initialize() {
        repository.createIfMissing(initialUsername, passwordEncoder.encode(initialPassword));
    }

    public AdminRepository.AdminUser authenticate(String username, String password) {
        AdminRepository.AdminUser user = repository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return user;
    }

    public AdminRepository.AdminUser current() {
        return repository.findCurrent().orElseThrow(() -> new IllegalStateException("管理员尚未初始化"));
    }

    public void changePassword(String oldPassword, String newPassword) {
        AdminRepository.AdminUser user = current();
        if (!passwordEncoder.matches(oldPassword, user.passwordHash())) {
            throw new IllegalArgumentException("原密码错误");
        }
        if (newPassword == null || newPassword.length() < 10) {
            throw new IllegalArgumentException("新密码至少需要 10 个字符");
        }
        if (newPassword.equals(oldPassword)) {
            throw new IllegalArgumentException("新密码不能与原密码相同");
        }
        repository.updatePassword(passwordEncoder.encode(newPassword));
    }
}
