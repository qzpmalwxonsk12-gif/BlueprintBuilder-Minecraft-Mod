package com.example.blueprintbuilder.blueprint;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes one layer CSV, <b>row by row</b> (streamed — never holds the whole layer/blueprint). Each cell is
 * double-quoted (block states contain commas), with {@code ""} escaping inner quotes.
 */
public final class BlueprintCSVWriter implements Closeable {

    private final BufferedWriter writer;
    private final StringBuilder sb = new StringBuilder(256); // reused across rows

    public BlueprintCSVWriter(Path file) throws IOException {
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }

    public void writeRow(String[] cells) throws IOException {
        sb.setLength(0);
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(cells[i].replace("\"", "\"\"")).append('"');
        }
        writer.write(sb.toString());
        writer.newLine();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
