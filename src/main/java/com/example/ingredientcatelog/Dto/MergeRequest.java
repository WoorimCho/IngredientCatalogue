package com.example.ingredientcatelog.Dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Fold the {@code from} tags into {@code into}: every ingredient linked to a
 * {@code from} tag is relinked to {@code into}, then the {@code from} tags are
 * deleted. {@code into} is left untouched if it also appears in {@code from}.
 */
public record MergeRequest(@NotEmpty Set<Long> from, @NotNull Long into) {
}
