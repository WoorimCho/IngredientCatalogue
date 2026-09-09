package com.example.ingredientcatalogue.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import com.example.cataloguecommon.tag.Tag;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An entry in the catalogue.
 *
 * <p>This one entity covers what the old monolith split into {@code Food} and
 * {@code Ingredient} — they are unified here. Any sub-distinction (e.g. "raw,
 * edible without cooking" vs "processed") is expressed with a tag such as
 * {@code form:raw}, not a column.
 *
 * <p>Note there is no {@code hasRecipe} flag: whether a recipe produces or uses
 * this entry is the Recipe catalogue's concern, not this service's, and a boolean
 * here would only ever drift out of sync.
 */
@Entity
@Table(name = "ingredient")
@Getter
public class Ingredient {

    @Id
    @GeneratedValue
    private Long id;

    @Setter
    @Column(name = "name", nullable = false, unique = true)
    private String name;

    /**
     * Tags are a many-to-many owned from this side. The {@code ingredient_tag}
     * join table is the only place the links live.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "ingredient_tag",
            joinColumns = @JoinColumn(name = "ingredient_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new LinkedHashSet<>();

    /**
     * Optional nutrition facts, per {@code nutr_basis_grams} grams. Every column
     * is nullable; {@link Nutrition#isPresent()} says whether there is real data.
     */
    @Setter
    @Embedded
    private Nutrition nutrition;

    protected Ingredient() {
        // for JPA
    }

    public Ingredient(String name) {
        this.name = name;
    }

    public void addTag(Tag tag) {
        tags.add(tag);
    }

    public void removeTag(Tag tag) {
        tags.remove(tag);
    }

    public void removeTagsById(Set<Long> tagIds) {
        tags.removeIf(tag -> tagIds.contains(tag.getId()));
    }

    public void replaceTags(Set<Tag> newTags) {
        tags.clear();
        tags.addAll(newTags);
    }
}
