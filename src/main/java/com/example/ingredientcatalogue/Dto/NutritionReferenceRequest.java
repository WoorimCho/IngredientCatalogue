package com.example.ingredientcatalogue.Dto;

import com.example.ingredientcatalogue.Model.NutritionReference;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Add a row to the nutrition reference. {@code name} is required and unique
 * (stored lower-case); every figure is optional and per {@code basisGrams}
 * grams (defaults to 100).
 */
public record NutritionReferenceRequest(
        @NotBlank @Size(max = 200) String name,
        @PositiveOrZero Double basisGrams,
        @PositiveOrZero Double kcal,
        @PositiveOrZero Double proteinG,
        @PositiveOrZero Double carbsG,
        @PositiveOrZero Double fatG,
        @PositiveOrZero Double fiberG,
        @PositiveOrZero Double sugarG,
        @PositiveOrZero Double sodiumMg) {

    public NutritionReference toEntity() {
        NutritionReference r = new NutritionReference();
        r.setName(name.trim().toLowerCase(java.util.Locale.ROOT));
        r.setBasisGrams(basisGrams == null ? 100.0 : basisGrams);
        r.setKcal(kcal);
        r.setProteinG(proteinG);
        r.setCarbsG(carbsG);
        r.setFatG(fatG);
        r.setFiberG(fiberG);
        r.setSugarG(sugarG);
        r.setSodiumMg(sodiumMg);
        return r;
    }
}
