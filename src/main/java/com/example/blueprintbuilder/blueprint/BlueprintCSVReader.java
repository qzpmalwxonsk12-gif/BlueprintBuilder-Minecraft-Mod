package com.example.blueprintbuilder.blueprint;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads one layer CSV, <b>row by row</b> (streamed — never loads the whole blueprint). Quote-aware so block
 * states containing commas survive. {@link #readRow()} returns {@code null} at end of file.
 */
public final class BlueprintCSVReader implements Closeable {

    private final BufferedReader reader;

    public BlueprintCSVReader(Path file) throws IOException {
        this.reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
    }

    /** Next row's cells, or {@code null} at end of file. */
    public String[] readRow() throws IOException {
        String line = reader.readLine();
        return line == null ? null : splitCells(line);
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    /** Splits a CSV line on commas, honouring double-quoted cells and the {@code ""} escape. */
    static String[] splitCells(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
