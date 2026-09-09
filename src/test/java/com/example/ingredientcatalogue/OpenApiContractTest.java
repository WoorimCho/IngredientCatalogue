package com.example.ingredientcatalogue;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test: every interaction below is validated — request <em>and</em>
 * response — against the checked-in {@code static/openapi.yaml}. If the
 * controllers drift from the published contract (a field renamed, a status code
 * changed, the page envelope reshaped) this fails.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiContractTest {

    private static OpenApiInteractionValidator validator;

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void loadSpec() throws Exception {
        String spec = new String(
                new ClassPathResource("static/openapi.yaml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        validator = OpenApiInteractionValidator.createForInlineApiSpecification(spec).build();
    }

    @BeforeEach
    void seed() throws Exception {
        // A couple of rows so the list/get/by-ids responses are non-trivial.
        mvc.perform(post("/api/ingredients").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"tofu\",\"tags\":[\"diet:vegan\",\"nut-free\"]}"));
        mvc.perform(post("/api/ingredients").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"peanut\",\"tags\":[\"diet:vegan\"]}"));
    }

    @Test
    void listIngredients_matchesContract() throws Exception {
        mvc.perform(get("/api/ingredients?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void searchIngredientsByTag_matchesContract() throws Exception {
        mvc.perform(get("/api/ingredients?tag=diet:vegan&tag=nut-free&match=any"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void getIngredient_matchesContract() throws Exception {
        String body = mvc.perform(post("/api/ingredients").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"rice\"}"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(get("/api/ingredients/{id}", id))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void getMissingIngredient_matchesContractAs404Problem() throws Exception {
        mvc.perform(get("/api/ingredients/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void createIngredient_matchesContract() throws Exception {
        mvc.perform(post("/api/ingredients").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"lentil\",\"tags\":[\"diet:vegan\"]}"))
                .andExpect(status().isCreated())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void createInvalidIngredient_matchesContractAs400Problem() throws Exception {
        mvc.perform(post("/api/ingredients").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void byIds_matchesContract() throws Exception {
        mvc.perform(get("/api/ingredients/by-ids?id=1&id=2&id=999999"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void listTags_matchesContract() throws Exception {
        mvc.perform(get("/api/tags?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void createTag_matchesContract() throws Exception {
        mvc.perform(post("/api/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cuisine:thai\",\"namespace\":\"cuisine\"}"))
                .andExpect(status().isCreated())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void createIngredientWithNutrition_matchesContract() throws Exception {
        mvc.perform(post("/api/ingredients").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"olive oil","tags":["diet:vegan"],
                                 "nutrition":{"basisGrams":100,"kcal":884,"fatG":100,"proteinG":0,"carbsG":0}}"""))
                .andExpect(status().isCreated())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void createIngredientWithDensity_matchesContract() throws Exception {
        String body = mvc.perform(post("/api/ingredients").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"whole milk\",\"densityGPerMl\":1.03}"))
                .andExpect(status().isCreated())
                .andExpect(openApi().isValid(validator))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*?\"id\":(\\d+).*", "$1"));

        // round-trips on the response, and a value update sticks
        org.assertj.core.api.Assertions.assertThat(body).contains("\"densityGPerMl\":1.03");
        mvc.perform(put("/api/ingredients/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"whole milk\",\"densityGPerMl\":1.04}"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.densityGPerMl").value(1.04));
    }

    @Test
    void setNutrition_matchesContract() throws Exception {
        String body = mvc.perform(post("/api/ingredients").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"butter\"}"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*?\"id\":(\\d+).*", "$1"));

        mvc.perform(put("/api/ingredients/{id}/nutrition", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kcal\":717,\"fatG\":81.1,\"proteinG\":0.9}"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void nutritionReference_matchesContract() throws Exception {
        mvc.perform(get("/api/nutrition-reference?name=sugar"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void csvImport_returnsTheDocumentedShape() throws Exception {
        // swagger-request-validator can't reconstruct a multipart body from MockMvc,
        // so the ImportResult response shape is checked directly instead.
        MockMultipartFile file = new MockMultipartFile("file", "in.csv", "text/csv",
                "name,tags,kcal\nquinoa,diet:vegan,368\nquinoa,,1\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/ingredients/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").value(2))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[0].message").isString());
    }
}
