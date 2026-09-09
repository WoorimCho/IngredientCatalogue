package com.example.ingredientcatalogue.Controllers;

import com.example.ingredientcatalogue.Dto.NutritionReferenceRequest;
import com.example.ingredientcatalogue.Dto.NutritionReferenceResponse;
import com.example.ingredientcatalogue.Model.NutritionReference;
import com.example.ingredientcatalogue.Repositories.NutritionReferenceRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The nutrition reference — figures for common foods, per {@code basisGrams}
 * grams. Flyway seeds 44 rows ({@code V3}); more can be added via
 * {@code POST}. The BFF / UI uses it to offer "fill nutrition from reference"
 * for an ingredient that has none of its own.
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
     * (case-insensitive), ordered by name.
     */
    @GetMapping
    public List<NutritionReferenceResponse> list(@RequestParam(required = false) String name) {
        List<NutritionReference> rows = StringUtils.hasText(name)
                ? repository.findByNameContainingIgnoreCaseOrderByNameAsc(name.trim())
                : repository.findAllByOrderByNameAsc();
        return rows.stream().map(NutritionReferenceResponse::from).toList();
    }

    /** Add a reference row. 409 if the name is already taken. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NutritionReferenceResponse add(@Valid @RequestBody NutritionReferenceRequest request) {
        repository.findByNameIgnoreCase(request.name().trim()).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a reference row named \"" + existing.getName() + "\" already exists");
        });
        return NutritionReferenceResponse.from(repository.save(request.toEntity()));
    }
}
