package com.example.ingredientcatalogue.Services;

import com.example.cataloguecommon.Csv;
import com.example.ingredientcatalogue.Dto.ImportResult;
import com.example.ingredientcatalogue.Dto.ImportResult.RowError;
import com.example.ingredientcatalogue.Dto.IngredientRequest;
import com.example.ingredientcatalogue.Dto.NutritionDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Bulk-creates ingredients from a CSV.
 *
 * <p>Header row required; columns matched by name (case-insensitive), order
 * free. {@code name} is the only required column. {@code tags} is a
 * {@code ;}-separated list. Any of {@code basisGrams, kcal, proteinG, carbsG,
 * fatG, fiberG, sugarG, sodiumMg} present (and non-blank) builds the ingredient's
 * nutrition.
 *
 * <p>Each row is created in its own transaction ({@code NOT_SUPPORTED} here so
 * the per-call {@link IngredientService#create} transactions are independent) —
 * a bad row is reported, not fatal.
 */
@Service
public class CsvIngredientImporter {

    private static final List<String> NUTRITION_COLS = List.of(
            "basisgrams", "kcal", "proteing", "carbsg", "fatg", "fiberg", "sugarg", "sodiummg");

    private final IngredientService ingredientService;

    public CsvIngredientImporter(IngredientService ingredientService) {
        this.ingredientService = ingredientService;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportResult importCsv(String csv) {
        List<String[]> rows = Csv.parse(csv);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("the file has no rows");
        }
        Map<String, Integer> col = headerIndex(rows.get(0));
        if (!col.containsKey("name")) {
            throw new IllegalArgumentException("CSV must have a 'name' column");
        }

        int imported = 0;
        List<RowError> errors = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            long line = i + 1L;
            try {
                ingredientService.create(toRequest(rows.get(i), col));
                imported++;
            } catch (RuntimeException ex) {
                errors.add(new RowError(line, rootMessage(ex)));
            }
        }
        return new ImportResult(rows.size() - 1, imported, errors.size(), errors);
    }

    private static Map<String, Integer> headerIndex(String[] header) {
        return IntStream.range(0, header.length).boxed()
                .collect(Collectors.toMap(
                        i -> header[i].trim().toLowerCase(Locale.ROOT), i -> i, (a, b) -> a));
    }

    private static IngredientRequest toRequest(String[] row, Map<String, Integer> col) {
        String name = cell(row, col, "name");
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("missing name");
        }
        Set<String> tags = new LinkedHashSet<>();
        String tagCell = cell(row, col, "tags");
        if (StringUtils.hasText(tagCell)) {
            for (String t : tagCell.split(";")) {
                if (StringUtils.hasText(t)) {
                    tags.add(t.trim());
                }
            }
        }
        NutritionDto nutrition = nutrition(row, col);
        return new IngredientRequest(name.trim(), tags, nutrition);
    }

    private static NutritionDto nutrition(String[] row, Map<String, Integer> col) {
        boolean any = NUTRITION_COLS.stream().anyMatch(c -> StringUtils.hasText(cell(row, col, c)));
        if (!any) {
            return null;
        }
        return new NutritionDto(
                num(row, col, "basisgrams"), num(row, col, "kcal"), num(row, col, "proteing"),
                num(row, col, "carbsg"), num(row, col, "fatg"), num(row, col, "fiberg"),
                num(row, col, "sugarg"), num(row, col, "sodiummg"));
    }

    private static String cell(String[] row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return (i == null || i >= row.length) ? null : row[i];
    }

    private static Double num(String[] row, Map<String, Integer> col, String name) {
        String v = cell(row, col, name);
        if (!StringUtils.hasText(v)) {
            return null;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + name + "' is not a number: " + v);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        if (m != null && m.toLowerCase(Locale.ROOT).contains("uk_ingredient_name")) {
            return "an ingredient with that name already exists";
        }
        return m != null ? m : t.getClass().getSimpleName();
    }
}
