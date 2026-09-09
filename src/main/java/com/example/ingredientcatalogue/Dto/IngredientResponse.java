package com.example.ingredientcatalogue.Dto;

import com.example.cataloguecommon.tag.TagResponse;
import com.example.ingredientcatalogue.Model.Ingredient;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Comparator;
import java.util.List;

/**
 * {@code nutrition} and {@code densityGPerMl} are omitted entirely when the
 * ingredient has no such data (never serialized as {@code null}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngredientResponse(Long id, String name, List<TagResponse> tags,
                                 NutritionDto nutrition, Double densityGPerMl) {

    public static IngredientResponse from(Ingredient ingredient) {
        List<TagResponse> tags = ingredient.getTags().stream()
                .map(TagResponse::from)
                .sorted(Comparator.comparing(TagResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new IngredientResponse(
                ingredient.getId(), ingredient.getName(), tags,
                NutritionDto.from(ingredient.getNutrition()),
                ingredient.getDensityGPerMl());
    }
}
