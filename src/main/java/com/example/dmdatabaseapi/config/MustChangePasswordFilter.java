package com.example.dmdatabaseapi.config;

import com.example.dmdatabaseapi.repository.AdminRepository;
import com.example.dmdatabaseapi.service.AdminService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 首次登录后仅允许访问账号信息、改密和退出接口。 */
@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {
    private final AdminService adminService;

    public MustChangePasswordFilter(AdminService adminService) {
        this.adminService = adminService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String path = request.getRequestURI();
        if (authentication != null && authentication.isAuthenticated()
                && path.startsWith("/api/admin/") && !isAllowedAuthPath(path)) {
            AdminRepository.AdminUser user = adminService.current();
            if (user.mustChangePassword()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":403,\"message\":\"请先修改初始密码\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowedAuthPath(String path) {
        return path.startsWith("/api/admin/auth/");
    }
}
