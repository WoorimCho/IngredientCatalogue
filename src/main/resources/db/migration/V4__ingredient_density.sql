-- Volume<->mass in the recipe calculators needs a density per ingredient.
-- Nullable: unknown until someone fills it in. Rough guide (g/ml): water 1.0,
-- milk 1.03, oil 0.92, honey 1.42, all-purpose flour ~0.53, granulated sugar ~0.85.
alter table ingredient
    add column density_g_per_ml double;
