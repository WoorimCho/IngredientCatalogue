package com.example.ingredientcatalogue.Repositories;

import com.example.ingredientcatalogue.Model.Ingredient;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/**
 * Composable filters for the ingredient search endpoint — the {@code
 * JpaSpecificationExecutor} extension point the repository comment anticipated.
 * Tag filtering keeps its hand-tuned queries in {@code IngredientRepository};
 * this is just the name-fragment filter for now.
 */
public final class IngredientSpecifications {

    private IngredientSpecifications() {
    }

    public static Specification<Ingredient> nameContains(String query) {
        String needle = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, q, cb) -> cb.like(cb.lower(root.get("name")), needle);
    }
}
