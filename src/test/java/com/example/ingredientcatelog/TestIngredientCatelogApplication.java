package com.example.ingredientcatelog;

import org.springframework.boot.SpringApplication;

public class TestIngredientCatelogApplication {

    public static void main(String[] args) {
        SpringApplication.from(IngredientCatelogApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
