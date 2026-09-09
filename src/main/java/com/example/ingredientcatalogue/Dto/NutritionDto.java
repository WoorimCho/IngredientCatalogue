package com.example.ingredientcatalogue.Dto;

import com.example.ingredientcatalogue.Model.Nutrition;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Nutrition facts, per {@code basisGrams} grams (defaults to 100 when omitted).
 * Every figure is optional; unset figures are omitted from responses, not null.
 * Used on {@code IngredientRequest} / response and by
 * {@code PUT /api/ingredients/{id}/nutrition}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NutritionDto(
        @PositiveOrZero Double basisGrams,
        @PositiveOrZero Double kcal,
        @PositiveOrZero Double proteinG,
        @PositiveOrZero Double carbsG,
        @PositiveOrZero Double fatG,
        @PositiveOrZero Double fiberG,
        @PositiveOrZero Double sugarG,
        @PositiveOrZero Double sodiumMg) {

    public static NutritionDto from(Nutrition n) {
        if (n == null || !n.isPresent()) {
            return null;
        }
        return new NutritionDto(n.getBasisGrams(), n.getKcal(), n.getProteinG(), n.getCarbsG(),
                n.getFatG(), n.getFiberG(), n.getSugarG(), n.getSodiumMg());
    }

    /** A {@link Nutrition} for persistence; {@code basisGrams} defaults to 100. */
    public Nutrition toEntity() {
        Nutrition n = new Nutrition();
        n.setBasisGrams(basisGrams == null ? 100.0 : basisGrams);
        n.setKcal(kcal);
        n.setProteinG(proteinG);
        n.setCarbsG(carbsG);
        n.setFatG(fatG);
        n.setFiberG(fiberG);
        n.setSugarG(sugarG);
        n.setSodiumMg(sodiumMg);
        return n;
    }
}
