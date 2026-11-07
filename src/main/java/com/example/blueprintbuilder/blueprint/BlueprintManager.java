package com.example.blueprintbuilder.blueprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Owns Blueprint paths and naming. Each Blueprint is a self-contained folder under {@code blueprints/}:
 * {@code blueprints/<Name>/metadata.json} + {@code blueprints/<Name>/layers/layerNNN.csv}. Independent of
 * the builder (it resolves the folder itself).
 */
public final class BlueprintManager {

    private BlueprintManager() {}

    /** The {@code blueprints/} root inside the game directory (shared with the builder, but we use subfolders). */
    public static Path root() {
        return FabricLoader.getInstance().getGameDir().resolve("blueprints");
    }

    public static Path folder(String name) {
        return root().resolve(name);
    }

    public static Path metadataFile(String name) {
        return folder(name).resolve("metadata.json");
    }

    public static Path layersDir(String name) {
        return folder(name).resolve("layers");
    }

    public static Path layerFile(String name, int y) {
        return layersDir(name).resolve(layerFileName(y));
    }

    /** Zero-padded to at least 3 digits; widens automatically past 999. */
    public static String layerFileName(int y) {
        return String.format("layer%03d.csv", y);
    }

    /** A name is valid if it's a single safe folder name (no path separators / traversal). */
    public static boolean isValidName(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || name.equals(".")
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return false;
        }
        try {
            return folder(name).normalize().startsWith(root().normalize());
        } catch (InvalidPathException e) {
            return false; // characters the OS rejects in paths (e.g. * ? " < > | on Windows)
        }
    }

    /** True if a complete Blueprint (folder + metadata) exists. */
    public static boolean exists(String name) {
        return Files.isDirectory(folder(name)) && Files.isRegularFile(metadataFile(name));
    }

    public static void ensureLayersDir(String name) throws IOException {
        Files.createDirectories(layersDir(name));
    }

    /** Names of all complete Blueprints (folders containing metadata.json), for command suggestions. */
    public static List<String> list() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("metadata.json")))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(names::add);
        } catch (IOException ignored) {
            // best effort
        }
        return names;
    }
}
