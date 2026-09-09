package com.example.ingredientcatalogue.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A row in the static nutrition reference — figures for a common food,
 * per {@link #basisGrams} grams. Seeded by Flyway ({@code V3}); read-only via
 * {@code GET /api/nutrition-reference}. The BFF / UI uses it to offer
 * "fill nutrition from reference" when an ingredient has none of its own.
 */
@Entity
@Table(name = "nutrition_reference")
@Getter
@Setter
public class NutritionReference {

    @Id
    @GeneratedValue
    private Long id;

    /** Lower-case common name, e.g. "all-purpose flour", "chicken breast, raw". Unique. */
    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "basis_grams", nullable = false)
    private Double basisGrams = 100.0;

    @Column(name = "kcal")
    private Double kcal;

    @Column(name = "protein_g")
    private Double proteinG;

    @Column(name = "carbs_g")
    private Double carbsG;

    @Column(name = "fat_g")
    private Double fatG;

    @Column(name = "fiber_g")
    private Double fiberG;

    @Column(name = "sugar_g")
    private Double sugarG;

    @Column(name = "sodium_mg")
    private Double sodiumMg;
}
