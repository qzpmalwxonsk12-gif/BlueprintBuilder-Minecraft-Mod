package com.example.blueprintbuilder.blueprint.version;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

/**
 * Converts one block state (id + property strings) so it is valid for a selected {@link StructureVersion}.
 * Isolates <b>all</b> per-version block knowledge behind one call, so exporters stay version-agnostic.
 *
 * <p>Each {@link Rule} describes what a Minecraft version introduced. When the target version is older
 * than a rule, the rule is applied: <b>renames</b> map a block back to its old id (identical block, new
 * name — e.g. 1.20.3 renamed {@code grass} to {@code short_grass}), and <b>fallbacks</b> substitute a
 * visually/functionally similar block that already existed (e.g. {@code cherry_planks} →
 * {@code crimson_planks} below 1.20). A fallback of {@code ""} means there is no reasonable stand-in —
 * the block is dropped and the exporter leaves a gap.</p>
 *
 * <p>Fallbacks always point at blocks <i>older</i> than their rule, and the conversion loops until the
 * result is stable, so chains cascade correctly (cherry → crimson for a 1.19.4 target;
 * {@code mangrove_hanging_sign} → {@code mangrove_sign} → {@code dark_oak_sign} for 1.18.2). After a
 * substitution, properties are filtered against the substitute's real state definition so no invalid
 * property is ever written (a hanging sign's {@code attached} is dropped when it becomes a normal sign;
 * shared ones like {@code waterlogged}, {@code facing} or {@code rotation} survive).</p>
 *
 * <p>Non-{@code minecraft:} (modded) blocks pass through unchanged — the mod cannot know their history,
 * and the exporter reports them so the user can judge.</p>
 */
public final class VersionBlockConverter {

    /** Outcome for one block state. {@code substitutedFrom} is the original id when a fallback was used. */
    public record Converted(String blockId, Map<String, String> properties, @Nullable String substitutedFrom) {}

    /** What one Minecraft version introduced; applied when exporting to anything older. */
    private record Rule(int dataVersion, Map<String, String> renames, Map<String, String> fallbacks) {}

    /** Fallback value for "no reasonable substitute — drop the block (leave a gap)". */
    private static final String DROP = "";

    /** Substitution chains are short (≤3); this only guards against a bad future table edit. */
    private static final int MAX_HOPS = 8;

    private static final String MINECRAFT = "minecraft:";

    /** Newest first. A rule applies when {@code target.dataVersion() < rule.dataVersion()}. */
    private static final List<Rule> RULES = List.of(

            // ---------------------------------------------------------------- 1.21 (Tricky Trials)
            new Rule(3953, Map.of(), Map.ofEntries(
                    Map.entry("crafter", "crafting_table"),
                    Map.entry("trial_spawner", "spawner"),
                    Map.entry("vault", "chest"),
                    Map.entry("heavy_core", "iron_block"),
                    // chiseled copper → cut copper (same oxidation/wax level)
                    Map.entry("chiseled_copper", "cut_copper"),
                    Map.entry("exposed_chiseled_copper", "exposed_cut_copper"),
                    Map.entry("weathered_chiseled_copper", "weathered_cut_copper"),
                    Map.entry("oxidized_chiseled_copper", "oxidized_cut_copper"),
                    Map.entry("waxed_chiseled_copper", "waxed_cut_copper"),
                    Map.entry("waxed_exposed_chiseled_copper", "waxed_exposed_cut_copper"),
                    Map.entry("waxed_weathered_chiseled_copper", "waxed_weathered_cut_copper"),
                    Map.entry("waxed_oxidized_chiseled_copper", "waxed_oxidized_cut_copper"),
                    // copper grates → the matching solid copper block
                    Map.entry("copper_grate", "copper_block"),
                    Map.entry("exposed_copper_grate", "exposed_copper"),
                    Map.entry("weathered_copper_grate", "weathered_copper"),
                    Map.entry("oxidized_copper_grate", "oxidized_copper"),
                    Map.entry("waxed_copper_grate", "waxed_copper_block"),
                    Map.entry("waxed_exposed_copper_grate", "waxed_exposed_copper"),
                    Map.entry("waxed_weathered_copper_grate", "waxed_weathered_copper"),
                    Map.entry("waxed_oxidized_copper_grate", "waxed_oxidized_copper"),
                    // copper bulbs light up → redstone lamp (keeps the shared "lit" property)
                    Map.entry("copper_bulb", "redstone_lamp"),
                    Map.entry("exposed_copper_bulb", "redstone_lamp"),
                    Map.entry("weathered_copper_bulb", "redstone_lamp"),
                    Map.entry("oxidized_copper_bulb", "redstone_lamp"),
                    Map.entry("waxed_copper_bulb", "redstone_lamp"),
                    Map.entry("waxed_exposed_copper_bulb", "redstone_lamp"),
                    Map.entry("waxed_weathered_copper_bulb", "redstone_lamp"),
                    Map.entry("waxed_oxidized_copper_bulb", "redstone_lamp"),
                    // copper doors/trapdoors → iron (metal look; door properties are identical)
                    Map.entry("copper_door", "iron_door"),
                    Map.entry("exposed_copper_door", "iron_door"),
                    Map.entry("weathered_copper_door", "iron_door"),
                    Map.entry("oxidized_copper_door", "iron_door"),
                    Map.entry("waxed_copper_door", "iron_door"),
                    Map.entry("waxed_exposed_copper_door", "iron_door"),
                    Map.entry("waxed_weathered_copper_door", "iron_door"),
                    Map.entry("waxed_oxidized_copper_door", "iron_door"),
                    Map.entry("copper_trapdoor", "iron_trapdoor"),
                    Map.entry("exposed_copper_trapdoor", "iron_trapdoor"),
                    Map.entry("weathered_copper_trapdoor", "iron_trapdoor"),
                    Map.entry("oxidized_copper_trapdoor", "iron_trapdoor"),
                    Map.entry("waxed_copper_trapdoor", "iron_trapdoor"),
                    Map.entry("waxed_exposed_copper_trapdoor", "iron_trapdoor"),
                    Map.entry("waxed_weathered_copper_trapdoor", "iron_trapdoor"),
                    Map.entry("waxed_oxidized_copper_trapdoor", "iron_trapdoor"),
                    // tuff building set → andesite/stone bricks (keeps stair/slab/wall shapes)
                    Map.entry("tuff_stairs", "andesite_stairs"),
                    Map.entry("tuff_slab", "andesite_slab"),
                    Map.entry("tuff_wall", "andesite_wall"),
                    Map.entry("chiseled_tuff", "tuff"),
                    Map.entry("polished_tuff", "polished_andesite"),
                    Map.entry("polished_tuff_stairs", "polished_andesite_stairs"),
                    Map.entry("polished_tuff_slab", "polished_andesite_slab"),
                    Map.entry("polished_tuff_wall", "andesite_wall"),
                    Map.entry("tuff_bricks", "stone_bricks"),
                    Map.entry("tuff_brick_stairs", "stone_brick_stairs"),
                    Map.entry("tuff_brick_slab", "stone_brick_slab"),
                    Map.entry("tuff_brick_wall", "stone_brick_wall"),
                    Map.entry("chiseled_tuff_bricks", "chiseled_stone_bricks"))),

            // ------------------------------------------------- 1.20.3 (grass renamed to short_grass)
            new Rule(3698, Map.of("short_grass", "grass"), Map.of()),

            // ---------------------------------------------------------------- 1.20 (Trails & Tales)
            new Rule(3463, Map.of(), Map.ofEntries(
                    // cherry wood → crimson (closest pink/red wood; leaves → flowering azalea)
                    Map.entry("cherry_log", "crimson_stem"),
                    Map.entry("stripped_cherry_log", "stripped_crimson_stem"),
                    Map.entry("cherry_wood", "crimson_hyphae"),
                    Map.entry("stripped_cherry_wood", "stripped_crimson_hyphae"),
                    Map.entry("cherry_planks", "crimson_planks"),
                    Map.entry("cherry_stairs", "crimson_stairs"),
                    Map.entry("cherry_slab", "crimson_slab"),
                    Map.entry("cherry_fence", "crimson_fence"),
                    Map.entry("cherry_fence_gate", "crimson_fence_gate"),
                    Map.entry("cherry_door", "crimson_door"),
                    Map.entry("cherry_trapdoor", "crimson_trapdoor"),
                    Map.entry("cherry_button", "crimson_button"),
                    Map.entry("cherry_pressure_plate", "crimson_pressure_plate"),
                    Map.entry("cherry_sign", "crimson_sign"),
                    Map.entry("cherry_wall_sign", "crimson_wall_sign"),
                    Map.entry("cherry_leaves", "flowering_azalea_leaves"),
                    Map.entry("cherry_sapling", "flowering_azalea"),
                    Map.entry("potted_cherry_sapling", "potted_flowering_azalea_bush"),
                    // bamboo wood → jungle (thematic pair)
                    Map.entry("bamboo_planks", "jungle_planks"),
                    Map.entry("bamboo_mosaic", "jungle_planks"),
                    Map.entry("bamboo_block", "jungle_log"),
                    Map.entry("stripped_bamboo_block", "stripped_jungle_log"),
                    Map.entry("bamboo_stairs", "jungle_stairs"),
                    Map.entry("bamboo_mosaic_stairs", "jungle_stairs"),
                    Map.entry("bamboo_slab", "jungle_slab"),
                    Map.entry("bamboo_mosaic_slab", "jungle_slab"),
                    Map.entry("bamboo_fence", "jungle_fence"),
                    Map.entry("bamboo_fence_gate", "jungle_fence_gate"),
                    Map.entry("bamboo_door", "jungle_door"),
                    Map.entry("bamboo_trapdoor", "jungle_trapdoor"),
                    Map.entry("bamboo_button", "jungle_button"),
                    Map.entry("bamboo_pressure_plate", "jungle_pressure_plate"),
                    Map.entry("bamboo_sign", "jungle_sign"),
                    Map.entry("bamboo_wall_sign", "jungle_wall_sign"),
                    // hanging signs → the wood's normal sign (rotation/facing/waterlogged carry over)
                    Map.entry("oak_hanging_sign", "oak_sign"),
                    Map.entry("oak_wall_hanging_sign", "oak_wall_sign"),
                    Map.entry("spruce_hanging_sign", "spruce_sign"),
                    Map.entry("spruce_wall_hanging_sign", "spruce_wall_sign"),
                    Map.entry("birch_hanging_sign", "birch_sign"),
                    Map.entry("birch_wall_hanging_sign", "birch_wall_sign"),
                    Map.entry("jungle_hanging_sign", "jungle_sign"),
                    Map.entry("jungle_wall_hanging_sign", "jungle_wall_sign"),
                    Map.entry("acacia_hanging_sign", "acacia_sign"),
                    Map.entry("acacia_wall_hanging_sign", "acacia_wall_sign"),
                    Map.entry("dark_oak_hanging_sign", "dark_oak_sign"),
                    Map.entry("dark_oak_wall_hanging_sign", "dark_oak_wall_sign"),
                    Map.entry("mangrove_hanging_sign", "mangrove_sign"),
                    Map.entry("mangrove_wall_hanging_sign", "mangrove_wall_sign"),
                    Map.entry("crimson_hanging_sign", "crimson_sign"),
                    Map.entry("crimson_wall_hanging_sign", "crimson_wall_sign"),
                    Map.entry("warped_hanging_sign", "warped_sign"),
                    Map.entry("warped_wall_hanging_sign", "warped_wall_sign"),
                    Map.entry("cherry_hanging_sign", "crimson_sign"),
                    Map.entry("cherry_wall_hanging_sign", "crimson_wall_sign"),
                    Map.entry("bamboo_hanging_sign", "jungle_sign"),
                    Map.entry("bamboo_wall_hanging_sign", "jungle_wall_sign"),
                    // the rest of Trails & Tales
                    Map.entry("chiseled_bookshelf", "bookshelf"),
                    Map.entry("decorated_pot", "terracotta"),
                    Map.entry("suspicious_sand", "sand"),
                    Map.entry("suspicious_gravel", "gravel"),
                    Map.entry("pink_petals", "flowering_azalea"),
                    Map.entry("torchflower", "dandelion"),
                    Map.entry("torchflower_crop", DROP),
                    Map.entry("pitcher_plant", "peony"),
                    Map.entry("pitcher_crop", DROP),
                    Map.entry("sniffer_egg", "turtle_egg"),
                    Map.entry("calibrated_sculk_sensor", "sculk_sensor"),
                    Map.entry("piglin_head", "zombie_head"),
                    Map.entry("piglin_wall_head", "zombie_wall_head"))),

            // ---------------------------------------------------------------- 1.19 (The Wild Update)
            new Rule(3105, Map.of(), Map.ofEntries(
                    // mangrove wood → dark oak
                    Map.entry("mangrove_log", "dark_oak_log"),
                    Map.entry("stripped_mangrove_log", "stripped_dark_oak_log"),
                    Map.entry("mangrove_wood", "dark_oak_wood"),
                    Map.entry("stripped_mangrove_wood", "stripped_dark_oak_wood"),
                    Map.entry("mangrove_planks", "dark_oak_planks"),
                    Map.entry("mangrove_stairs", "dark_oak_stairs"),
                    Map.entry("mangrove_slab", "dark_oak_slab"),
                    Map.entry("mangrove_fence", "dark_oak_fence"),
                    Map.entry("mangrove_fence_gate", "dark_oak_fence_gate"),
                    Map.entry("mangrove_door", "dark_oak_door"),
                    Map.entry("mangrove_trapdoor", "dark_oak_trapdoor"),
                    Map.entry("mangrove_button", "dark_oak_button"),
                    Map.entry("mangrove_pressure_plate", "dark_oak_pressure_plate"),
                    Map.entry("mangrove_sign", "dark_oak_sign"),
                    Map.entry("mangrove_wall_sign", "dark_oak_wall_sign"),
                    Map.entry("mangrove_leaves", "dark_oak_leaves"),
                    Map.entry("mangrove_roots", "dark_oak_leaves"),
                    Map.entry("muddy_mangrove_roots", "coarse_dirt"),
                    Map.entry("mangrove_propagule", "oak_sapling"),
                    Map.entry("potted_mangrove_propagule", "potted_oak_sapling"),
                    // mud → earthy stand-ins, mud bricks → bricks (same shape family)
                    Map.entry("mud", "coarse_dirt"),
                    Map.entry("packed_mud", "dirt"),
                    Map.entry("mud_bricks", "bricks"),
                    Map.entry("mud_brick_stairs", "brick_stairs"),
                    Map.entry("mud_brick_slab", "brick_slab"),
                    Map.entry("mud_brick_wall", "brick_wall"),
                    // deep dark set (sculk_sensor itself is 1.17 and needs no mapping)
                    Map.entry("sculk", "black_concrete"),
                    Map.entry("sculk_catalyst", "black_concrete"),
                    Map.entry("sculk_shrieker", "black_concrete"),
                    Map.entry("sculk_vein", "glow_lichen"),
                    Map.entry("reinforced_deepslate", "chiseled_deepslate"),
                    // froglights → other light-emitting blocks
                    Map.entry("ochre_froglight", "glowstone"),
                    Map.entry("verdigris_froglight", "sea_lantern"),
                    Map.entry("pearlescent_froglight", "sea_lantern"),
                    Map.entry("frogspawn", DROP))));

    private VersionBlockConverter() {}

    /**
     * Converts one block state for the target version.
     *
     * @param blockId    full id, e.g. {@code minecraft:short_grass}
     * @param properties property name → value strings (sorted map); not modified
     * @return the (possibly renamed/substituted) state, or {@code null} when the block has no
     *         representation in the target version and should be left as a gap
     */
    @Nullable
    public static Converted convert(String blockId, Map<String, String> properties, StructureVersion target) {
        if (target.isCurrent() || !blockId.startsWith(MINECRAFT)) {
            return new Converted(blockId, properties, null); // current version / modded: pass through
        }
        String path = blockId.substring(MINECRAFT.length());
        boolean substituted = false;

        for (int hop = 0; hop < MAX_HOPS; hop++) {
            String next = applyOnce(path, target);
            if (next == null) {
                break; // valid for the target as-is
            }
            if (next.isEmpty()) {
                return null; // explicitly dropped
            }
            substituted |= !isRename(path, next, target);
            path = next;
        }

        String outId = MINECRAFT + path;
        Map<String, String> outProps = substituted ? filterProperties(outId, properties) : properties;
        return new Converted(outId, outProps, substituted ? blockId : null);
    }

    /** One conversion step: the first applicable rule's mapping, or {@code null} when none applies. */
    @Nullable
    private static String applyOnce(String path, StructureVersion target) {
        for (Rule rule : RULES) {
            if (target.dataVersion() >= rule.dataVersion()) {
                continue; // target already knows this version's blocks
            }
            String renamed = rule.renames().get(path);
            if (renamed != null) {
                return renamed;
            }
            String fallback = rule.fallbacks().get(path);
            if (fallback != null) {
                return fallback; // may be DROP ("")
            }
        }
        return null;
    }

    /** True when {@code from → to} was a pure rename (same block, older id) rather than a substitution. */
    private static boolean isRename(String from, String to, StructureVersion target) {
        for (Rule rule : RULES) {
            if (target.dataVersion() < rule.dataVersion() && to.equals(rule.renames().get(from))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps only the properties the substitute block actually has (checked against the live registry —
     * every substitute is an old, stable block that still exists in the current version, so its current
     * state definition matches the target's). Values are validated too, so nothing invalid is written.
     */
    private static Map<String, String> filterProperties(String blockId, Map<String, String> properties) {
        if (properties.isEmpty()) {
            return properties;
        }
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return Map.of(); // unknown substitute (should not happen) — safer to write no properties
        }
        Block block = Registries.BLOCK.get(id);
        Map<String, String> kept = new TreeMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            Property<?> property = block.getStateManager().getProperty(entry.getKey());
            if (property != null && property.parse(entry.getValue()).isPresent()) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }
}
