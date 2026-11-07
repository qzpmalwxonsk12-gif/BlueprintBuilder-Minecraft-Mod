package com.example.blueprintbuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Turns a .csv blueprint into a grid of block states.
 *
 * <p>The CSV is read as a plain grid: each line is a row, each comma-separated value is a cell.
 * Row 0 (the first line) is the top of the blueprint, column 0 (the first cell) is the left edge —
 * so the very first cell is "A1", the top-left start block.</p>
 *
 * <p>Per-cell rules:</p>
 * <ul>
 *   <li>Empty cell  → {@code null} (the build leaves whatever is already there; nothing is placed).</li>
 *   <li>A valid block id (e.g. {@code minecraft:oak_log}, or just {@code oak_log}) → that block's default state.</li>
 *   <li>Anything present but unrecognized as a block → {@code minecraft:air} ("just have air in its place").</li>
 * </ul>
 */
public final class BlueprintParser {

    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final BlockState VOID_SKIP_MARKER = null; // documented intent for "empty cell"

    private BlueprintParser() {}

    /**
     * Parses the file into a list of rows; each row is a list of cells. A cell is {@code null} when it
     * was empty (skip), otherwise it is the {@link BlockState} to place (possibly air).
     */
    public static List<List<BlockState>> parse(Path csv) throws IOException {
        List<List<BlockState>> grid = new ArrayList<>();

        // Stream line by line so a big blueprint's raw text is never held in memory alongside the grid.
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = splitCsvLine(line);
                List<BlockState> row = new ArrayList<>(cells.length);
                for (String cell : cells) {
                    row.add(cellToState(cell));
                }
                grid.add(row);
            }
        }
        return grid;
    }

    /** Removes a leading UTF-8 byte-order mark (U+FEFF) if present. */
    static String stripBom(String s) {
        if (!s.isEmpty() && s.charAt(0) == 0xFEFF) {
            return s.substring(1);
        }
        return s;
    }

    /** Converts one raw cell value into a block state, or {@code null} if the cell is empty (skip it). */
    @Nullable
    static BlockState cellToState(String raw) {
        // Strip a UTF-8 BOM (common when CSVs are exported from Excel) and trim whitespace.
        String s = stripBom(raw).trim();
        if (s.isEmpty()) {
            return VOID_SKIP_MARKER; // empty cell: leave the existing block untouched
        }

        // Allow bare block names (e.g. "oak_log") by defaulting the namespace to "minecraft".
        String idString = s.contains(":") ? s : "minecraft:" + s;
        Identifier id = Identifier.tryParse(idString);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            // Present but not a real block → air, per the spec.
            return AIR;
        }
        return Registries.BLOCK.get(id).getDefaultState();
    }

    /**
     * Splits a CSV line on commas, honoring double-quoted fields (so a quoted value may itself contain
     * commas) and the {@code ""} escape for a literal quote. Block ids never need quoting, but this keeps
     * spreadsheet-exported files from breaking.
     */
    static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); // escaped quote inside a quoted field
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
