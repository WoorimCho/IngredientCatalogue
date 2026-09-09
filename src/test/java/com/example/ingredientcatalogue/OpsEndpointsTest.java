package com.example.ingredientcatalogue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operational surface: Flyway migrates + Hibernate validates on boot (this class
 * loading at all proves that), plus actuator and the checked-in API contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "internal-auth.enabled=false")
class OpsEndpointsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void infoReportsBuildVersion() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.version").value("0.0.1-SNAPSHOT"));
    }

    @Test
    void prometheusScrapeIsExposed() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    void openApiContractIsServed() throws Exception {
        mvc.perform(get("/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ingredient Catalogue API")));
    }

    @Test
    void nutritionReferenceSeedLoaded() throws Exception {
        mvc.perform(get("/api/nutrition-reference?name=flour"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("all-purpose flour"))
                .andExpect(jsonPath("$[0].kcal").value(364.0));
    }
}
