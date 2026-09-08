package com.example.ingredientcatelog;

import com.example.ingredientcatelog.Model.Ingredient;
import com.example.ingredientcatelog.Model.Tag;
import com.example.ingredientcatelog.Repositories.IngredientRepository;
import com.example.ingredientcatelog.Repositories.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-layer test against the real (Testcontainers) MySQL — no web layer,
 * no services. Exercises the hand-written tag queries, including the
 * dialect-sensitive {@code group by … having count(distinct …)} in
 * {@code findByAllTagNames}.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class IngredientRepositoryDataJpaTest {

    @Autowired
    IngredientRepository ingredients;
    @Autowired
    TagRepository tags;

    private Tag vegan;
    private Tag nutFree;

    @BeforeEach
    void setUp() {
        vegan = tags.save(new Tag("diet:vegan"));
        nutFree = tags.save(new Tag("nut-free"));

        Ingredient tofu = new Ingredient("tofu");
        tofu.addTag(vegan);
        tofu.addTag(nutFree);
        ingredients.save(tofu);

        Ingredient peanut = new Ingredient("peanut");
        peanut.addTag(vegan);
        ingredients.save(peanut);
    }

    @Test
    void findByAllTagNames_requiresEveryTag() {
        Page<Ingredient> page = ingredients.findByAllTagNames(
                List.of("diet:vegan", "nut-free"), 2, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement()
                .extracting(Ingredient::getName).isEqualTo("tofu");
    }

    @Test
    void findByAnyTagName_requiresAtLeastOne() {
        Page<Ingredient> page = ingredients.findByAnyTagName(
                List.of("diet:vegan", "nut-free"), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findByTagsIdIn_returnsIngredientsCarryingAnyTag() {
        assertThat(ingredients.findByTags_IdIn(List.of(nutFree.getId())))
                .extracting(Ingredient::getName).containsExactly("tofu");
    }
}
