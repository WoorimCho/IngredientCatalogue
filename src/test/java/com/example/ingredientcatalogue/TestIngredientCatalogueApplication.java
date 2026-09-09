package com.example.ingredientcatalogue;

import org.springframework.boot.SpringApplication;

public class TestIngredientCatalogueApplication {

    public static void main(String[] args) {
        SpringApplication.from(IngredientCatalogueApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
