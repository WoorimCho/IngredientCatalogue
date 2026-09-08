package com.example.ingredientcatelog.Services;

import com.example.ingredientcatelog.Dto.IngredientRequest;
import com.example.ingredientcatelog.Dto.IngredientResponse;
import com.example.ingredientcatelog.Exception.NotFoundException;
import com.example.ingredientcatelog.Model.Ingredient;
import com.example.ingredientcatelog.Repositories.IngredientRepository;
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
    public Page<IngredientResponse> search(Collection<String> tags, String match, Pageable pageable) {
        Set<String> names = tags == null ? Set.of() : tags.stream()
                .map(TagService::normalise)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (names.isEmpty()) {
            return ingredientRepository.findAll(pageable).map(IngredientResponse::from);
        }

        Page<Ingredient> page = "any".equalsIgnoreCase(match)
                ? ingredientRepository.findByAnyTagName(names, pageable)
                : ingredientRepository.findByAllTagNames(names, names.size(), pageable);
        return page.map(IngredientResponse::from);
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
        return IngredientResponse.from(ingredientRepository.save(ingredient));
    }

    @Override
    public IngredientResponse update(long id, IngredientRequest request) {
        Ingredient ingredient = require(id);
        ingredient.setName(request.name().trim());
        ingredient.replaceTags(tagService.resolve(request.tagsOrEmpty()));
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
