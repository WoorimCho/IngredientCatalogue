package com.example.ingredientcatalogue.Services;

import com.example.ingredientcatalogue.Dto.IngredientRequest;
import com.example.ingredientcatalogue.Dto.IngredientResponse;
import com.example.ingredientcatalogue.Dto.NutritionDto;
import com.example.cataloguecommon.NotFoundException;
import com.example.cataloguecommon.tag.TagService;
import com.example.ingredientcatalogue.Model.Ingredient;
import com.example.ingredientcatalogue.Repositories.IngredientRepository;
import com.example.ingredientcatalogue.Repositories.IngredientSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    public Page<IngredientResponse> search(String name, Collection<String> tags, String match, Pageable pageable) {
        Set<String> names = tags == null ? Set.of() : tags.stream()
                .map(TagService::normalise)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean hasName = StringUtils.hasText(name);

        // Tag filter present: keep the hand-tuned queries whose paging/count are
        // known-good; narrow by name afterwards when both are given.
        if (!names.isEmpty()) {
            Page<Ingredient> page = "any".equalsIgnoreCase(match)
                    ? ingredientRepository.findByAnyTagName(names, pageable)
                    : ingredientRepository.findByAllTagNames(names, names.size(), pageable);
            if (!hasName) {
                return page.map(IngredientResponse::from);
            }
            String needle = name.trim().toLowerCase(java.util.Locale.ROOT);
            List<IngredientResponse> filtered = page.getContent().stream()
                    .filter(i -> i.getName() != null && i.getName().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    .map(IngredientResponse::from)
                    .toList();
            return new org.springframework.data.domain.PageImpl<>(filtered, pageable, filtered.size());
        }

        // Name only.
        if (hasName) {
            return ingredientRepository.findAll(IngredientSpecifications.nameContains(name), pageable)
                    .map(IngredientResponse::from);
        }

        // No filters: the plain paged list (identical to before).
        return ingredientRepository.findAll(pageable).map(IngredientResponse::from);
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
