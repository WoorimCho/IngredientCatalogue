package com.example.ingredientcatalogue.Controllers;

import com.example.ingredientcatalogue.Dto.ImportResult;
import com.example.ingredientcatalogue.Dto.IngredientRequest;
import com.example.ingredientcatalogue.Dto.IngredientResponse;
import com.example.ingredientcatalogue.Dto.NutritionDto;
import com.example.cataloguecommon.PageResponse;
import com.example.cataloguecommon.tag.TagNamesRequest;
import com.example.ingredientcatalogue.Services.CsvIngredientImporter;
import com.example.ingredientcatalogue.Services.IngredientService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/ingredients")
public class IngredientController {

    private final IngredientService ingredientService;
    private final CsvIngredientImporter csvImporter;

    public IngredientController(IngredientService ingredientService, CsvIngredientImporter csvImporter) {
        this.ingredientService = ingredientService;
        this.csvImporter = csvImporter;
    }

    /**
     * List ingredients, optionally filtered by a name fragment and/or by tag.
     * Tag terms match a tag name anywhere ("vegan" finds "diet:vegan").
     * <pre>
     * GET /api/ingredients
     * GET /api/ingredients?name=oat                                     name contains "oat" (autocomplete)
     * GET /api/ingredients?tag=vegan&amp;tag=nut-free                     has ALL of them (default)
     * GET /api/ingredients?tag=vegan&amp;tag=keto&amp;match=any            has ANY of them
     * GET /api/ingredients?tag=oil&amp;notTag=nut                         "oil"-ish, but nothing "nut"-ish
     * GET /api/ingredients?page=0&amp;size=20&amp;sort=name,asc
     * </pre>
     */
    @GetMapping
    public PageResponse<IngredientResponse> list(
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(name = "match", defaultValue = "all") String match,
            @RequestParam(name = "notTag", required = false) List<String> excludeTags,
            // Sort by id, not name: the tag filter uses SELECT DISTINCT with joins,
            // and some databases reject ordering by a column outside that projection.
            // Callers can still pass ?sort=name,asc explicitly when not filtering by tag.
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return PageResponse.of(ingredientService.search(name, tags, match, excludeTags, pageable));
    }

    @GetMapping("/{id}")
    public IngredientResponse get(@PathVariable long id) {
        return ingredientService.get(id);
    }

    /**
     * Batch lookup by id — for cross-service resolution (e.g. the BFF composing a
     * recipe from RecipeCatalogue). Unknown ids are omitted from the result.
     * <pre>GET /api/ingredients/by-ids?id=10&amp;id=11&amp;id=12</pre>
     */
    @GetMapping("/by-ids")
    public List<IngredientResponse> byIds(@RequestParam("id") List<Long> ids) {
        return ingredientService.getByIds(ids);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IngredientResponse create(@Valid @RequestBody IngredientRequest request) {
        return ingredientService.create(request);
    }

    @PutMapping("/{id}")
    public IngredientResponse update(@PathVariable long id, @Valid @RequestBody IngredientRequest request) {
        return ingredientService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        ingredientService.delete(id);
    }

    /** Attach tags (get-or-create by name) to an ingredient. */
    @PostMapping("/{id}/tags")
    public IngredientResponse addTags(@PathVariable long id, @Valid @RequestBody TagNamesRequest request) {
        return ingredientService.addTags(id, request.tags());
    }

    /** Detach a single tag from an ingredient. The tag itself is left alone. */
    @DeleteMapping("/{id}/tags/{tagName}")
    public IngredientResponse removeTag(@PathVariable long id, @PathVariable String tagName) {
        return ingredientService.removeTag(id, tagName);
    }

    /**
     * Replace an ingredient's nutrition facts (per {@code basisGrams} grams,
     * default 100). An empty body clears them.
     */
    @PutMapping("/{id}/nutrition")
    public IngredientResponse setNutrition(@PathVariable long id,
                                          @Valid @RequestBody(required = false) NutritionDto nutrition) {
        return ingredientService.setNutrition(id, nutrition);
    }

    /**
     * Bulk-create ingredients from a CSV upload ({@code multipart/form-data},
     * part name {@code file}). Header row required; columns: {@code name}
     * (required), {@code tags} ({@code ;}-separated), and any of
     * {@code basisGrams,kcal,proteinG,carbsG,fatG,fiberG,sugarG,sodiumMg}.
     * Always 200 — per-row problems are in the response body.
     */
    @PostMapping(path = "/import", consumes = "multipart/form-data")
    public ImportResult importCsv(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("the uploaded file is empty");
        }
        try {
            return csvImporter.importCsv(new String(file.getBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the uploaded file", e);
        }
    }
}
