package com.example.ingredientcatalogue.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

/**
 * Nutrition facts for an {@link Ingredient}, all <em>per {@link #basisGrams}
 * grams</em> of the ingredient (typically per 100 g).
 *
 * <p>Embedded, and entirely optional — an ingredient with no data simply has
 * every column null. The Phase 4 calculators (in the BFF) read this via the
 * {@code /api/ingredients/by-ids} batch endpoint, convert a recipe line's
 * amount to grams, and scale.
 *
 * <p>Static reference values for common foods live in the {@code
 * nutrition_reference} table ({@link NutritionReference}); this embeddable is
 * the per-ingredient copy, editable independently.
 */
@Embeddable
@Getter
@Setter
public class Nutrition {

    /** The reference amount these figures describe, in grams. Defaults to 100. */
    @Column(name = "nutr_basis_grams")
    private Double basisGrams;

    @Column(name = "nutr_kcal")
    private Double kcal;

    @Column(name = "nutr_protein_g")
    private Double proteinG;

    @Column(name = "nutr_carbs_g")
    private Double carbsG;

    @Column(name = "nutr_fat_g")
    private Double fatG;

    @Column(name = "nutr_fiber_g")
    private Double fiberG;

    @Column(name = "nutr_sugar_g")
    private Double sugarG;

    @Column(name = "nutr_sodium_mg")
    private Double sodiumMg;

    /** True when at least one figure is set — i.e. this is real data, not an empty embed. */
    public boolean isPresent() {
        return kcal != null || proteinG != null || carbsG != null || fatG != null
                || fiberG != null || sugarG != null || sodiumMg != null;
    }
}
