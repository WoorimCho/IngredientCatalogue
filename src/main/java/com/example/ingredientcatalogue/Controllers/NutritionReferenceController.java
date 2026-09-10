package com.example.ingredientcatalogue.Controllers;

import com.example.cataloguecommon.NotFoundException;
import com.example.ingredientcatalogue.Dto.NutritionReferenceRequest;
import com.example.ingredientcatalogue.Dto.NutritionReferenceResponse;
import com.example.ingredientcatalogue.Model.NutritionReference;
import com.example.ingredientcatalogue.Repositories.NutritionReferenceRepository;
import com.example.ingredientcatalogue.Sorting;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The nutrition reference — figures for common foods, per {@code basisGrams}
 * grams. Flyway seeds 44 rows ({@code V3}); more can be added via {@code POST}
 * and an existing row edited via {@code PUT /{id}}. The BFF / UI uses it to
 * offer "fill nutrition from reference" for an ingredient that has none of its own.
 */
@RestController
@RequestMapping("/api/nutrition-reference")
public class NutritionReferenceController {

    private final NutritionReferenceRepository repository;

    public NutritionReferenceController(NutritionReferenceRepository repository) {
        this.repository = repository;
    }

    /**
     * Every reference row, or those whose name contains {@code name}
     * (case-insensitive). Sorted by {@code ?sort=} (default {@code name,asc});
     * sortable: {@code name}, {@code kcal}, {@code proteinG}, {@code carbsG},
     * {@code fatG}, {@code fiberG}, {@code sugarG}, {@code sodiumMg},
     * {@code basisGrams}. Rows missing a figure sort last.
     */
    @GetMapping
    public List<NutritionReferenceResponse> list(
            @RequestParam(required = false) String name,
            @SortDefault(sort = "name") Sort sort) {
        Sort effective = Sorting.nullsLast(sort);
        List<NutritionReference> rows = StringUtils.hasText(name)
                ? repository.findByNameContainingIgnoreCase(name.trim(), effective)
                : repository.findAll(effective);
        return rows.stream().map(NutritionReferenceResponse::from).toList();
    }

    /** Add a reference row. 409 if the name is already taken. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NutritionReferenceResponse add(@Valid @RequestBody NutritionReferenceRequest request) {
        repository.findByNameIgnoreCase(request.normalisedName()).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a reference row named \"" + existing.getName() + "\" already exists");
        });
        return NutritionReferenceResponse.from(repository.save(request.toEntity()));
    }

    /**
     * Replace an existing row's figures (and optionally rename it). 404 if the
     * row doesn't exist; 409 if the new name is already used by another row.
     */
    @PutMapping("/{id}")
    public NutritionReferenceResponse update(@PathVariable long id,
                                             @Valid @RequestBody NutritionReferenceRequest request) {
        NutritionReference row = repository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Nutrition reference", id));
        repository.findByNameIgnoreCase(request.normalisedName())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "a reference row named \"" + other.getName() + "\" already exists");
                });
        return NutritionReferenceResponse.from(repository.save(request.applyTo(row)));
    }
}
