package com.example.ingredientcatalogue.Dto;

import com.example.cataloguecommon.tag.MergeRequest;
import com.example.cataloguecommon.tag.TagNamesRequest;
import com.example.cataloguecommon.tag.TagService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SECURITY.md N4, at the annotation level: no Spring context, no database. The
 * limits on tags come from {@link TagService}, shared with RecipeCatalogue, so this
 * covers the shared tag DTOs as well. HTTP-level proof is in {@code TagSearchIntegrationTest}.
 */
class RequestLimitsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    private static Set<String> tags(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "t" + i)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> paths(Object bean) {
        return validator.validate(bean).stream().map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    void ingredientWithTheMaximumNumberOfTagsIsValid() {
        assertThat(validator.validate(new IngredientRequest("Flour", tags(TagService.MAX_TAGS_PER_REQUEST), null, null)))
                .isEmpty();
    }

    @Test
    void ingredientWithOneTagTooManyIsRejected() {
        assertThat(paths(new IngredientRequest("Flour", tags(TagService.MAX_TAGS_PER_REQUEST + 1), null, null)))
                .containsExactly("tags");
    }

    @Test
    void ingredientTagThatIsTooLongIsRejected() {
        Set<String> tooLong = Set.of("a".repeat(TagService.MAX_TAG_LENGTH + 1));
        assertThat(paths(new IngredientRequest("Flour", tooLong, null, null))).hasSize(1)
                .allMatch(p -> p.startsWith("tags"));
    }

    @Test
    void attachTagsBodyIsBoundedToo() {
        assertThat(validator.validate(new TagNamesRequest(tags(TagService.MAX_TAGS_PER_REQUEST)))).isEmpty();
        assertThat(paths(new TagNamesRequest(tags(TagService.MAX_TAGS_PER_REQUEST + 1)))).containsExactly("tags");
        assertThat(paths(new TagNamesRequest(Set.of("a".repeat(TagService.MAX_TAG_LENGTH + 1))))).hasSize(1);
    }

    @Test
    void mergeSourcesAreBounded() {
        Set<Long> ok = IntStream.rangeClosed(1, TagService.MAX_TAGS_PER_REQUEST).mapToObj(Long::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(validator.validate(new MergeRequest(ok, 999L))).isEmpty();

        Set<Long> tooMany = IntStream.rangeClosed(1, TagService.MAX_TAGS_PER_REQUEST + 1).mapToObj(Long::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(paths(new MergeRequest(tooMany, 999L))).containsExactly("from");
    }
}
