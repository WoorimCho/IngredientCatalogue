package com.example.ingredientcatalogue.Repositories;

import com.example.ingredientcatalogue.Model.NutritionReference;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NutritionReferenceRepository extends JpaRepository<NutritionReference, Long> {

    List<NutritionReference> findByNameContainingIgnoreCase(String fragment, Sort sort);

    Optional<NutritionReference> findByNameIgnoreCase(String name);
}
