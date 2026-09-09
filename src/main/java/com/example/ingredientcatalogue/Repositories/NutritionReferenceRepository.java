package com.example.ingredientcatalogue.Repositories;

import com.example.ingredientcatalogue.Model.NutritionReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NutritionReferenceRepository extends JpaRepository<NutritionReference, Long> {

    List<NutritionReference> findByNameContainingIgnoreCaseOrderByNameAsc(String fragment);

    List<NutritionReference> findAllByOrderByNameAsc();

    Optional<NutritionReference> findByNameIgnoreCase(String name);
}
