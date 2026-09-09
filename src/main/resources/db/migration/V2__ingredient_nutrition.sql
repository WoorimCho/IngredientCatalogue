-- Phase 4: nutrition.
--   * per-ingredient facts, embedded on `ingredient` (all columns nullable -
--     an ingredient with no data has every nutr_* null)
--   * a static reference table of common foods, seeded by V3

alter table ingredient
    add column nutr_basis_grams double,
    add column nutr_kcal        double,
    add column nutr_protein_g   double,
    add column nutr_carbs_g     double,
    add column nutr_fat_g       double,
    add column nutr_fiber_g     double,
    add column nutr_sugar_g     double,
    add column nutr_sodium_mg   double;

create table nutrition_reference (
    id          bigint       not null,
    name        varchar(255) not null,
    basis_grams double       not null,
    kcal        double,
    protein_g   double,
    carbs_g     double,
    fat_g       double,
    fiber_g     double,
    sugar_g     double,
    sodium_mg   double,
    primary key (id),
    constraint uk_nutrition_reference_name unique (name)
) engine = InnoDB;

create table nutrition_reference_seq (next_val bigint) engine = InnoDB;
insert into nutrition_reference_seq (next_val) values (1);
