package com.example.dmdatabaseapi.controller;

import com.example.dmdatabaseapi.entity.ApiResponse;
import com.example.dmdatabaseapi.repository.AdminRepository;
import com.example.dmdatabaseapi.service.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 提供管理端登录、退出、当前账号和首次改密接口。 */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {
    private final AdminService adminService;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AdminAuthController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.success(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }

    @PostMapping("/login")
    public ApiResponse<UserView> login(@RequestBody LoginRequest body,
                                       HttpServletRequest request, HttpServletResponse response) {
        AdminRepository.AdminUser user = adminService.authenticate(body.username(), body.password());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user.username(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return ApiResponse.success(new UserView(user.username(), user.mustChangePassword()));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        AdminRepository.AdminUser user = adminService.current();
        return ApiResponse.success(new UserView(user.username(), user.mustChangePassword()));
    }

    @PostMapping("/change-password")
    public ApiResponse<String> changePassword(@RequestBody ChangePasswordRequest body) {
        adminService.changePassword(body.oldPassword(), body.newPassword());
        return ApiResponse.success("密码修改成功");
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.success("已退出登录");
    }

    public record LoginRequest(String username, String password) {
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    public record UserView(String username, boolean mustChangePassword) {
    }
}
