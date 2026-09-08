package com.example.ingredientcatelog.Dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Body for creating or updating an ingredient. {@code tags} are given by name;
 * any that don't exist yet are created (get-or-create).
 */
public record IngredientRequest(
        @NotBlank @Size(max = 255) String name,
        Set<String> tags) {

    public Set<String> tagsOrEmpty() {
        return tags == null ? Set.of() : tags;
    }
}
