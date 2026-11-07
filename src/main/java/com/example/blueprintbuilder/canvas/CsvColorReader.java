package com.example.blueprintbuilder.canvas;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * Parses a canvas CSV into a grid of RGB colours. <b>Parsing only</b> — it knows nothing about blocks,
 * the world or placement.
 *
 * <p>Format (spreadsheet-cell model): each line is one image row, and each <b>cell</b> holds one pixel as
 * {@code R,G,B} — e.g. cell A1 = {@code 234,343,34}. Because a cell's value itself contains commas, cells
 * are split quote-aware, so a spreadsheet export quotes them:</p>
 *
 * <pre>"234,343,34","12,200,5","0,0,255"</pre>
 *
 * <p>Graceful handling: blank lines are skipped; an empty cell is a gap (no block placed) and keeps the
 * row aligned; values are clamped to 0–255; a cell that isn't exactly three integers becomes a gap and is
 * counted as invalid for user feedback.</p>
 */
public final class CsvColorReader {

    /** One parsed cell colour. */
    public record Rgb(int red, int green, int blue) {}

    /** Parsed grid plus how many cells were invalid/malformed (for user feedback). */
    public record Result(List<List<Rgb>> rows, int invalidCells) {
        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    private CsvColorReader() {}

    public static Result read(Path csv) throws IOException {
        List<List<Rgb>> rows = new ArrayList<>();
        int invalid = 0;

        // Stream line by line: large images would otherwise hold the whole raw file in memory
        // (readAllLines) on top of the parsed grid being built.
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                String line = stripBom(rawLine);
                if (line.trim().isEmpty()) {
                    continue; // skip blank lines
                }

                List<String> cells = splitCells(line);
                List<Rgb> row = new ArrayList<>(cells.size());
                for (String cell : cells) {
                    String value = cell.trim();
                    if (value.isEmpty()) {
                        row.add(null); // empty cell = gap (intentional, not counted as invalid)
                        continue;
                    }
                    Rgb rgb = parseCell(value);
                    if (rgb == null) {
                        row.add(null); // malformed cell = gap, but keep row alignment
                        invalid++;
                    } else {
                        row.add(rgb);
                    }
                }
                rows.add(row);
            }
        }

        return new Result(rows, invalid);
    }

    /** Parses a single cell value "R,G,B" into a colour, or {@code null} if it isn't three integers. */
    @Nullable
    private static Rgb parseCell(String cell) {
        String[] parts = cell.split(",");
        if (parts.length != 3) {
            return null;
        }
        Integer r = parseInt(parts[0]);
        Integer g = parseInt(parts[1]);
        Integer b = parseInt(parts[2]);
        if (r == null || g == null || b == null) {
            return null;
        }
        return new Rgb(clamp(r), clamp(g), clamp(b));
    }

    /**
     * Splits a CSV line into cells, honouring double-quoted cells (so a cell's value may contain commas) and
     * the {@code ""} escape for a literal quote.
     */
    private static List<String> splitCells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); // escaped quote inside a quoted cell
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
        return out;
    }

    @Nullable
    private static Integer parseInt(String token) {
        String t = token.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String stripBom(String s) {
        if (!s.isEmpty() && s.charAt(0) == 0xFEFF) {
            return s.substring(1);
        }
        return s;
    }
}
