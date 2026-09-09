package com.example.ingredientcatalogue.Repositories;

import com.example.ingredientcatalogue.Model.Ingredient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface IngredientRepository
        extends JpaRepository<Ingredient, Long>, JpaSpecificationExecutor<Ingredient> {

    // JpaSpecificationExecutor is the extension point for future dynamic
    // filters (by name fragment, namespace, created-date, ...). The two tag
    // searches below are hand-written so their paging and count behaviour stay
    // predictable.

    /** Ingredients carrying at least one of the given (already normalised) tag names. */
    @Query("""
           select distinct i from Ingredient i
           join i.tags t
           where t.name in :names
           """)
    Page<Ingredient> findByAnyTagName(@Param("names") Collection<String> names, Pageable pageable);

    /**
     * Ingredients carrying every one of the given (already normalised) tag
     * names. {@code count} must equal the number of distinct names requested.
     *
     * <p>The count query returns one row per matching ingredient and Spring
     * Data uses the row count as the total, which is why it selects {@code i.id}
     * rather than {@code count(...)}.
     */
    @Query(value = """
                   select i from Ingredient i
                   join i.tags t
                   where t.name in :names
                   group by i
                   having count(distinct t.id) = :count
                   """,
           countQuery = """
                   select i.id from Ingredient i
                   join i.tags t
                   where t.name in :names
                   group by i.id
                   having count(distinct t.id) = :count
                   """)
    Page<Ingredient> findByAllTagNames(@Param("names") Collection<String> names,
                                       @Param("count") long count,
                                       Pageable pageable);

    /** Every ingredient linked to any of the given tag ids (used by tag delete / merge). */
    List<Ingredient> findByTags_IdIn(Collection<Long> tagIds);
}
