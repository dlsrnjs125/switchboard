package io.github.dlsrnjs125.switchboard.controlplane;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dlsrnjs125.switchboard.controlplane.application.ControlPlaneService;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class ControlPlaneHttpSecurityIntegrationTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresIntegrationSupport.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PostgresIntegrationSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresIntegrationSupport.POSTGRES::getPassword);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ControlPlaneService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        new JdbcTemplate(dataSource).execute("TRUNCATE TABLE tenants CASCADE");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        service.createTenant("tenant-a", "Tenant A", "alice");
        service.createTenant("tenant-b", "Tenant B", "bob");
    }

    @Test
    void exercisesJwtControllerValidationTenantScopeAndConflictResponses() throws Exception {
        mvc.perform(get("/v1/tenants/tenant-a/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String project = """
                {"projectKey":"checkout","name":"Checkout"}
                """;
        mvc.perform(post("/v1/tenants/tenant-a/projects")
                        .with(jwt().jwt(token -> token.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(project))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantKey").value("tenant-a"))
                .andExpect(jsonPath("$.projectKey").value("checkout"));

        mvc.perform(get("/v1/tenants/tenant-b/projects")
                        .with(jwt().jwt(token -> token.subject("alice"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(post("/v1/tenants/tenant-a/projects")
                        .with(jwt().jwt(token -> token.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(project))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));

        mvc.perform(post("/v1/tenants/tenant-a/projects")
                        .with(jwt().jwt(token -> token.subject("alice")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"billing","name":"Billing","unknown":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void exposesPrometheusToTheInfrastructureScraperWithoutApiAuthentication() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }
}
