package com.example.ingredientcatalogue.Repositories;

import com.example.ingredientcatalogue.Model.Ingredient;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.Locale;

/**
 * Composable filters for the ingredient search endpoint — the {@code
 * JpaSpecificationExecutor} extension point the repository comment anticipated.
 *
 * <p>Tag terms match a tag name <em>anywhere</em> (case-insensitive substring),
 * to line up with the tag search / autocomplete — typing "vegan" finds
 * ingredients tagged {@code diet:vegan}. "has all tags" is one join per term
 * rather than a grouped {@code having count}, so the generated count query stays
 * simple.
 */
public final class IngredientSpecifications {

    private IngredientSpecifications() {
    }

    public static Specification<Ingredient> nameContains(String query) {
        String needle = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, q, cb) -> cb.like(cb.lower(root.get("name")), needle);
    }

    /** Ingredient has at least one tag whose name contains any of the terms. */
    public static Specification<Ingredient> hasAnyTag(Collection<String> tagTerms) {
        return (root, q, cb) -> {
            q.distinct(true);
            Expression<String> name = cb.lower(root.join("tags").get("name"));
            Predicate any = cb.disjunction();
            for (String term : tagTerms) {
                any = cb.or(any, cb.like(name, like(term)));
            }
            return any;
        };
    }

    /** For every term, the ingredient has some tag whose name contains it. */
    public static Specification<Ingredient> hasAllTags(Collection<String> tagTerms) {
        return (root, q, cb) -> {
            q.distinct(true);
            Predicate all = cb.conjunction();
            for (String term : tagTerms) {
                all = cb.and(all, cb.like(cb.lower(root.join("tags").get("name")), like(term)));
            }
            return all;
        };
    }

    /** Ingredients with no tag matching any of the terms. {@code NOT IN (subquery)} — leaves the main joins alone. */
    public static Specification<Ingredient> lacksAllTags(Collection<String> tagTerms) {
        return (root, q, cb) -> {
            Subquery<Long> tagged = q.subquery(Long.class);
            Root<Ingredient> other = tagged.from(Ingredient.class);
            Expression<String> name = cb.lower(other.join("tags").get("name"));
            Predicate anyMatch = cb.disjunction();
            for (String term : tagTerms) {
                anyMatch = cb.or(anyMatch, cb.like(name, like(term)));
            }
            tagged.select(other.get("id")).where(anyMatch);
            return cb.not(root.get("id").in(tagged));
        };
    }

    private static String like(String term) {
        return "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
