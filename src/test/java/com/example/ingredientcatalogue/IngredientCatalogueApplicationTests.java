package com.example.ingredientcatalogue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "internal-auth.enabled=false")
@SpringBootTest
class IngredientCatalogueApplicationTests {

    @Test
    void contextLoads() {
    }

}
