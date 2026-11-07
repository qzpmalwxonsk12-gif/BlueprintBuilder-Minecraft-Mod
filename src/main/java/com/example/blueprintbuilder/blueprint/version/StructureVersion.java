package com.example.blueprintbuilder.blueprint.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

/**
 * The mod's <b>version selection system</b>: every Minecraft version an export can target, with the
 * per-version facts exporters need — the structure {@code DataVersion} stamp, the datapack folder name
 * (Mojang renamed {@code data/<ns>/structures/} to the singular {@code data/<ns>/structure/} in 1.21) and
 * whether the version has the {@code /place} command (added in 1.19; before that, Structure Blocks only).
 *
 * <p>Pure data, ordered newest first, so future export formats (Litematica, WorldEdit schematics, …) can
 * reuse the same selection without duplicating version knowledge. Adding a version is one list entry;
 * block/state differences between versions live in {@link VersionBlockConverter}, not here.</p>
 *
 * <p>Versions older than 1.18.2 are deliberately not offered: they predate the Caves &amp; Cliffs block
 * additions and would need far larger conversion tables to guarantee valid output.</p>
 */
public record StructureVersion(String id, int dataVersion, String datapackFolder, boolean hasPlaceCommand) {

    /** Supported target versions, newest first. DataVersion values are the vanilla release constants. */
    public static final List<StructureVersion> SUPPORTED = List.of(
            new StructureVersion("1.21.1", 3955, "structure", true),
            new StructureVersion("1.21", 3953, "structure", true),
            new StructureVersion("1.20.6", 3839, "structures", true),
            new StructureVersion("1.20.4", 3700, "structures", true),
            new StructureVersion("1.20.1", 3465, "structures", true),
            new StructureVersion("1.19.4", 3337, "structures", true),
            new StructureVersion("1.18.2", 2975, "structures", false));

    /** The version this mod itself runs on (the newest supported one). */
    public static final StructureVersion CURRENT = SUPPORTED.get(0);

    /** Looks up a version by its id string (e.g. {@code "1.21.1"}). */
    public static Optional<StructureVersion> byId(@Nullable String id) {
        if (id != null) {
            for (StructureVersion version : SUPPORTED) {
                if (version.id.equals(id)) {
                    return Optional.of(version);
                }
            }
        }
        return Optional.empty();
    }

    /** All supported ids, for command tab-completion. */
    public static List<String> ids() {
        List<String> ids = new ArrayList<>(SUPPORTED.size());
        for (StructureVersion version : SUPPORTED) {
            ids.add(version.id);
        }
        return ids;
    }

    /** Human-readable list of supported ids for error messages. */
    public static String idList() {
        return String.join(", ", ids());
    }

    public boolean isCurrent() {
        return dataVersion == CURRENT.dataVersion;
    }
}
