package com.example.ingredientcatelog.Dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for creating or updating a {@code Tag}. */
public record TagRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 60) String namespace,
        @Size(max = 500) String description) {
}
