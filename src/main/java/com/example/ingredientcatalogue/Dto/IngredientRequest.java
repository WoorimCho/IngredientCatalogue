package com.example.ingredientcatalogue.Dto;

import com.example.cataloguecommon.tag.TagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Body for creating or updating an ingredient. {@code tags} are given by name;
 * any that don't exist yet are created (get-or-create). {@code nutrition} and
 * {@code densityGPerMl} are optional; on update, {@code null} leaves the existing
 * value untouched (use {@code PUT /api/ingredients/{id}/nutrition} to set or
 * clear nutrition explicitly).
 */
public record IngredientRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = TagService.MAX_TAGS_PER_REQUEST) Set<@Size(max = TagService.MAX_TAG_LENGTH) String> tags,
        @Valid NutritionDto nutrition,
        @Positive Double densityGPerMl) {

    public Set<String> tagsOrEmpty() {
        return tags == null ? Set.of() : tags;
    }
}
