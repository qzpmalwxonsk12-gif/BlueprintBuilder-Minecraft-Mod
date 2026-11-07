package com.example.blueprintbuilder.blueprint;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * The {@code metadata.json} of a Blueprint. Plain fields serialized with Gson, so future versions can add
 * fields (block entities, entities, compression, thumbnails, …) without breaking older readers — unknown
 * fields are ignored on read and {@code formatVersion} guards incompatible changes.
 */
public class BlueprintMetadata {

    public int formatVersion = 1;
    public String name;
    public int width;
    public int height;
    public int depth;
    public String minecraftVersion;
    public String exportDate;
    public long blockCount;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public void write(Path file) throws IOException {
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    /** Height (layer count) beyond anything a real export can produce — guards corrupted metadata. */
    private static final int MAX_DIMENSION = 65_536;

    public static BlueprintMetadata read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            BlueprintMetadata meta = GSON.fromJson(reader, BlueprintMetadata.class);
            if (meta == null) {
                throw new IOException("metadata.json is empty");
            }
            // The importer iterates `height` layer files, so a corrupted/hand-edited value like
            // 2 billion would hang the game. Real exports are bounded by the world height (~384).
            if (meta.width <= 0 || meta.height <= 0 || meta.depth <= 0
                    || meta.width > MAX_DIMENSION || meta.height > MAX_DIMENSION || meta.depth > MAX_DIMENSION) {
                throw new IOException("metadata.json has invalid dimensions ("
                        + meta.width + "×" + meta.height + "×" + meta.depth + ")");
            }
            return meta;
        } catch (JsonParseException e) {
            throw new IOException("metadata.json is corrupted: " + e.getMessage(), e);
        }
    }
}
