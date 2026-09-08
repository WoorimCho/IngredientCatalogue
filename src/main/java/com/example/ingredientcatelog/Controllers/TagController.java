package com.example.ingredientcatelog.Controllers;

import com.example.ingredientcatelog.Dto.MergeRequest;
import com.example.ingredientcatelog.Dto.TagRequest;
import com.example.ingredientcatelog.Dto.TagResponse;
import com.example.ingredientcatelog.Services.TagService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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

@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /**
     * List / autocomplete tags.
     * <pre>
     * GET /api/tags
     * GET /api/tags?prefix=nu        name starts with "nu" (autocomplete)
     * GET /api/tags?namespace=diet   every tag in the "diet" namespace
     * </pre>
     */
    @GetMapping
    public Page<TagResponse> list(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String namespace,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return tagService.list(prefix, namespace, pageable);
    }

    @GetMapping("/{id}")
    public TagResponse get(@PathVariable long id) {
        return tagService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TagResponse create(@Valid @RequestBody TagRequest request) {
        return tagService.create(request);
    }

    @PutMapping("/{id}")
    public TagResponse update(@PathVariable long id, @Valid @RequestBody TagRequest request) {
        return tagService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        tagService.delete(id);
    }

    /** Fold one or more tags into another, moving every ingredient link across. */
    @PostMapping("/merge")
    public TagResponse merge(@Valid @RequestBody MergeRequest request) {
        return tagService.merge(request);
    }
}
