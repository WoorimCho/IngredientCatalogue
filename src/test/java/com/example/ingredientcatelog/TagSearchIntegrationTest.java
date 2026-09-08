package com.example.ingredientcatelog;

import com.example.ingredientcatelog.Repositories.IngredientRepository;
import com.example.ingredientcatelog.Repositories.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real HTTP endpoints against a Testcontainers MySQL. Covers the bits
 * compilation and {@code contextLoads} can't: tag normalisation, the match=all
 * grouped query and its total count, match=any, and merge. Body assertions go
 * through the repositories to stay independent of the JSON library version.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TagSearchIntegrationTest {

    private static final Pattern TOTAL = Pattern.compile("\"totalElements\":(\\d+)");

    @Autowired
    MockMvc mvc;
    @Autowired
    IngredientRepository ingredientRepository;
    @Autowired
    TagRepository tagRepository;

    @BeforeEach
    void reset() {
        ingredientRepository.deleteAll();
        tagRepository.deleteAll();
    }

    @Test
    void createNormalisesAndGetOrCreatesTags() throws Exception {
        create("Peanut", "Allergen:Peanut", " diet:vegan ");

        assertThat(tagRepository.findByName("allergen:peanut")).isPresent();
        assertThat(tagRepository.findByName("diet:vegan")).isPresent();
        assertThat(tagRepository.count()).isEqualTo(2);

        // Same names, different casing/spacing -> re-linked, no new tags.
        create("Peanut Butter", "allergen:peanut", "DIET:VEGAN");
        assertThat(tagRepository.count()).isEqualTo(2);
    }

    @Test
    void searchMatchAllVersusAny() throws Exception {
        create("Tofu", "diet:vegan", "nut-free");
        create("Peanut", "diet:vegan");
        create("Cashew", "nut-free");

        assertThat(total("?tag=diet:vegan&tag=nut-free")).isEqualTo(1);       // only Tofu has both
        assertThat(total("?tag=diet:vegan&tag=nut-free&match=any")).isEqualTo(3);
        assertThat(total("?tag=diet:carnivore")).isZero();
    }

    @Test
    void byIdsReturnsOnlyTheIngredientsThatExist() throws Exception {
        create("Rice Noodles");
        create("Tamarind Paste");
        long rice = ingredientRepository.findAll().iterator().next().getId();

        String body = mvc.perform(get("/api/ingredients/by-ids?id=" + rice + "&id=999999"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Rice Noodles");
        assertThat(body).doesNotContain("999999");
        assertThat(body).startsWith("[").endsWith("]");
    }

    @Test
    void mergeMovesLinksThenDeletesSourceTag() throws Exception {
        create("Cashew", "nutfree");
        create("Almond", "nut-free");
        long canonical = tagRepository.findByName("nut-free").orElseThrow().getId();
        long dupe = tagRepository.findByName("nutfree").orElseThrow().getId();

        mvc.perform(post("/api/tags/merge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\":[" + dupe + "],\"into\":" + canonical + "}"))
                .andExpect(status().isOk());

        assertThat(tagRepository.findById(dupe)).isEmpty();
        assertThat(total("?tag=nut-free&match=any")).isEqualTo(2);
    }

    private void create(String name, String... tags) throws Exception {
        String tagArray = String.join(",", Arrays.stream(tags).map(t -> "\"" + t + "\"").toList());
        mvc.perform(post("/api/ingredients").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"tags\":[" + tagArray + "]}"))
                .andExpect(status().isCreated());
    }

    private long total(String query) throws Exception {
        String body = mvc.perform(get("/api/ingredients" + query))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = TOTAL.matcher(body);
        assertThat(matcher.find()).as("totalElements in %s", body).isTrue();
        return Long.parseLong(matcher.group(1));
    }
}
