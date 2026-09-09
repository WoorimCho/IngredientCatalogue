package com.example.ingredientcatalogue.Services;

import com.example.cataloguecommon.tag.Tag;
import com.example.cataloguecommon.tag.TagOwnerCleanup;
import com.example.ingredientcatalogue.Model.Ingredient;
import com.example.ingredientcatalogue.Repositories.IngredientRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The Ingredient side of {@link TagOwnerCleanup}: there is no DB cascade on the
 * {@code ingredient_tag} join table, so links are cleared in code before a tag
 * is deleted or merged (see {@code catalogue-common}'s {@code TagServiceImpl}).
 */
@Component
class IngredientTagOwnerCleanup implements TagOwnerCleanup {

    private final IngredientRepository ingredients;

    IngredientTagOwnerCleanup(IngredientRepository ingredients) {
        this.ingredients = ingredients;
    }

    @Override
    public void detachAll(Set<Long> tagIds) {
        List<Ingredient> linked = ingredients.findByTags_IdIn(tagIds);
        linked.forEach(ingredient -> ingredient.removeTagsById(tagIds));
        ingredients.saveAll(linked);
        ingredients.flush();
    }

    @Override
    public void retag(Set<Long> fromIds, Tag into) {
        List<Ingredient> affected = ingredients.findByTags_IdIn(fromIds);
        for (Ingredient ingredient : affected) {
            ingredient.removeTagsById(fromIds);
            ingredient.addTag(into);
        }
        ingredients.saveAll(affected);
        ingredients.flush();
    }
}
