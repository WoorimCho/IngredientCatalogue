package com.example.ingredientcatelog.Dto;

import com.example.ingredientcatelog.Model.Ingredient;

import java.util.Comparator;
import java.util.List;

public record IngredientResponse(Long id, String name, List<TagResponse> tags) {

    public static IngredientResponse from(Ingredient ingredient) {
        List<TagResponse> tags = ingredient.getTags().stream()
                .map(TagResponse::from)
                .sorted(Comparator.comparing(TagResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new IngredientResponse(ingredient.getId(), ingredient.getName(), tags);
    }
}
