package com.example.ingredientcatelog.Dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/** Body for attaching tags to an ingredient: {@code {"tags": ["diet:vegan", "nut-free"]}}. */
public record TagNamesRequest(@NotEmpty Set<String> tags) {
}
