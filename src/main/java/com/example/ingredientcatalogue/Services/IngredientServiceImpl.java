package com.example.ingredientcatalogue.Services;

import com.example.ingredientcatalogue.Dto.IngredientRequest;
import com.example.ingredientcatalogue.Dto.IngredientResponse;
import com.example.ingredientcatalogue.Dto.NutritionDto;
import com.example.cataloguecommon.NotFoundException;
import com.example.cataloguecommon.tag.TagService;
import com.example.ingredientcatalogue.Model.Ingredient;
import com.example.ingredientcatalogue.Repositories.IngredientRepository;
import com.example.ingredientcatalogue.Repositories.IngredientSpecifications;
import com.example.ingredientcatalogue.Sorting;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class IngredientServiceImpl implements IngredientService {

    private final IngredientRepository ingredientRepository;
    private final TagService tagService;

    public IngredientServiceImpl(IngredientRepository ingredientRepository, TagService tagService) {
        this.ingredientRepository = ingredientRepository;
        this.tagService = tagService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<IngredientResponse> search(String name, Collection<String> tags, String match,
                                           Collection<String> excludeTags, Pageable pageable) {
        pageable = Sorting.nullsLast(pageable);   // kcal / protein / … are nullable; keep empties at the end
        Set<String> tagTerms = normaliseTags(tags);
        Set<String> excludeTerms = normaliseTags(excludeTags);

        List<Specification<Ingredient>> parts = new ArrayList<>();
        if (StringUtils.hasText(name)) {
            parts.add(IngredientSpecifications.nameContains(name));
        }
        if (!tagTerms.isEmpty()) {
            parts.add("any".equalsIgnoreCase(match)
                    ? IngredientSpecifications.hasAnyTag(tagTerms)
                    : IngredientSpecifications.hasAllTags(tagTerms));
        }
        if (!excludeTerms.isEmpty()) {
            parts.add(IngredientSpecifications.lacksAllTags(excludeTerms));
        }

        if (parts.isEmpty()) {
            return ingredientRepository.findAll(pageable).map(IngredientResponse::from);
        }
        Specification<Ingredient> spec = parts.get(0);
        for (int i = 1; i < parts.size(); i++) {
            spec = spec.and(parts.get(i));
        }
        return ingredientRepository.findAll(spec, pageable).map(IngredientResponse::from);
    }

    private static Set<String> normaliseTags(Collection<String> raw) {
        return raw == null ? Set.of() : raw.stream()
                .map(TagService::normalise)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    @Transactional(readOnly = true)
    public IngredientResponse get(long id) {
        return IngredientResponse.from(require(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<IngredientResponse> getByIds(Collection<Long> ids) {
        if (ids.size() > 500) {
            throw new IllegalArgumentException("at most 500 ids per request");
        }
        return ingredientRepository.findAllById(ids).stream()
                .map(IngredientResponse::from)
                .toList();
    }

    @Override
    public IngredientResponse create(IngredientRequest request) {
        Ingredient ingredient = new Ingredient(request.name().trim());
        ingredient.replaceTags(tagService.resolve(request.tagsOrEmpty()));
        if (request.nutrition() != null) {
            ingredient.setNutrition(request.nutrition().toEntity());
        }
        ingredient.setDensityGPerMl(request.densityGPerMl());
        return IngredientResponse.from(ingredientRepository.save(ingredient));
    }

    @Override
    public IngredientResponse update(long id, IngredientRequest request) {
        Ingredient ingredient = require(id);
        ingredient.setName(request.name().trim());
        ingredient.replaceTags(tagService.resolve(request.tagsOrEmpty()));
        // null nutrition on update = leave as-is; use setNutrition(...) to change it.
        if (request.nutrition() != null) {
            ingredient.setNutrition(request.nutrition().toEntity());
        }
        // same convention for density: null = leave as-is.
        if (request.densityGPerMl() != null) {
            ingredient.setDensityGPerMl(request.densityGPerMl());
        }
        return IngredientResponse.from(ingredientRepository.save(ingredient));
    }

    @Override
    public IngredientResponse setNutrition(long id, NutritionDto nutrition) {
        Ingredient ingredient = require(id);
        ingredient.setNutrition(nutrition == null ? null : nutrition.toEntity());
        return IngredientResponse.from(ingredientRepository.save(ingredient));
    }

    @Override
    public void delete(long id) {
        ingredientRepository.delete(require(id));
    }

    @Override
    public IngredientResponse addTags(long id, Set<String> tagNames) {
        Ingredient ingredient = require(id);
        tagService.resolve(tagNames).forEach(ingredient::addTag);
        return IngredientResponse.from(ingredientRepository.save(ingredient));
    }

    @Override
    public IngredientResponse removeTag(long id, String tagName) {
        Ingredient ingredient = require(id);
        String normalised = TagService.normalise(tagName);
        ingredient.getTags().removeIf(tag -> tag.getName().equals(normalised));
        return IngredientResponse.from(ingredientRepository.save(ingredient));
    }

    private Ingredient require(long id) {
        return ingredientRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Ingredient", id));
    }
}
