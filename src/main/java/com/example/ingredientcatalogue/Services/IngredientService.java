package com.example.ingredientcatalogue.Services;

import com.example.ingredientcatalogue.Dto.IngredientRequest;
import com.example.ingredientcatalogue.Dto.IngredientResponse;
import com.example.ingredientcatalogue.Dto.NutritionDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface IngredientService {

    /**
     * List ingredients, optionally filtered by a name fragment and/or by tag.
     * Tag terms match a tag name anywhere (case-insensitive substring).
     *
     * @param name        case-insensitive substring of the ingredient name; {@code null}/blank means "no filter"
     * @param tags        tag terms to require; {@code null} or empty means "no filter"
     * @param match       {@code "all"} (default) requires every term, {@code "any"} requires at least one
     * @param excludeTags tag terms to exclude — an ingredient with any matching tag is dropped
     */
    Page<IngredientResponse> search(String name, Collection<String> tags, String match,
                                    Collection<String> excludeTags, Pageable pageable);

    IngredientResponse get(long id);

    /** Batch lookup by id for cross-service resolution. Missing ids are simply absent from the result. */
    List<IngredientResponse> getByIds(Collection<Long> ids);

    IngredientResponse create(IngredientRequest request);

    IngredientResponse update(long id, IngredientRequest request);

    void delete(long id);

    IngredientResponse addTags(long id, Set<String> tagNames);

    IngredientResponse removeTag(long id, String tagName);

    /** Replace (or clear, with {@code null}) an ingredient's nutrition facts. */
    IngredientResponse setNutrition(long id, NutritionDto nutrition);
}
