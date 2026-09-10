package com.example.ingredientcatalogue;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Sort tweak shared by the ingredient search and the nutrition reference: most
 * of the sortable columns (kcal, protein, …) are nullable, and MySQL puts NULLs
 * <em>first</em> on an ascending sort. Rewrite every order so rows with no value
 * always land at the end, whichever direction you pick.
 */
public final class Sorting {

    private Sorting() {
    }

    public static Sort nullsLast(Sort sort) {
        if (sort.isUnsorted()) {
            return sort;
        }
        return Sort.by(sort.stream().map(Sort.Order::nullsLast).toList());
    }

    public static Pageable nullsLast(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), nullsLast(sort));
    }
}
