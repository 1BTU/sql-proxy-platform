package com.example.dmdatabaseapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/security-integration.db",
        "app.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "app.admin.username=admin",
        "app.admin.password=Admin@123456"
})
@AutoConfigureMockMvc
class SecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectsAdminAndDisablesLegacyAnonymousEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/proxies")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/query")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/schema")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tables")).andExpect(status().isNotFound());
    }

    @Test
    void loginRequiresCsrfAndReturnsForcedPasswordChange() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\"Admin@123456\"}";
        mockMvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));
    }
}
