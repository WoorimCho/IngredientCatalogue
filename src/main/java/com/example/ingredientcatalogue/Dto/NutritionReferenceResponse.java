package com.example.ingredientcatalogue.Dto;

import com.example.ingredientcatalogue.Model.NutritionReference;

/** A row of the static nutrition reference, per {@code basisGrams} grams. */
public record NutritionReferenceResponse(
        Long id,
        String name,
        Double basisGrams,
        Double kcal,
        Double proteinG,
        Double carbsG,
        Double fatG,
        Double fiberG,
        Double sugarG,
        Double sodiumMg) {

    public static NutritionReferenceResponse from(NutritionReference r) {
        return new NutritionReferenceResponse(
                r.getId(), r.getName(), r.getBasisGrams(), r.getKcal(), r.getProteinG(),
                r.getCarbsG(), r.getFatG(), r.getFiberG(), r.getSugarG(), r.getSodiumMg());
    }
}
