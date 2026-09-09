package com.example.ingredientcatalogue.Dto;

import java.util.List;

/**
 * Outcome of a bulk import. Always HTTP 200 — per-row problems are reported in
 * {@link #errors}, not by failing the whole call.
 *
 * @param rows     data rows seen (header excluded)
 * @param imported rows that created an ingredient
 * @param skipped  rows that did not (see {@code errors} for why)
 */
public record ImportResult(int rows, int imported, int skipped, List<RowError> errors) {

    public record RowError(long line, String message) {
    }
}
