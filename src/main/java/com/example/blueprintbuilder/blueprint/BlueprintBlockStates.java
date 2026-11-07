package com.example.blueprintbuilder.blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

/**
 * Converts a {@link BlockState} to/from its full command-style string (id + every property), e.g.
 * {@code minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]}, so directional blocks
 * (logs, stairs, slabs, trapdoors, fences, …) round-trip exactly. Properties are sorted by name for stable
 * output. Block-entity / NBT data is intentionally out of scope (a documented future extension).
 */
public final class BlueprintBlockStates {

    private BlueprintBlockStates() {}

    /** Full string form: {@code namespace:id} or {@code namespace:id[prop=value,...]}. */
    public static String serialize(BlockState state) {
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        if (properties.isEmpty()) {
            return id.toString();
        }
        properties.sort(Comparator.comparing(Property::getName));

        StringBuilder sb = new StringBuilder(id.toString()).append('[');
        boolean first = true;
        for (Property<?> property : properties) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(property.getName()).append('=').append(valueName(state, property));
        }
        return sb.append(']').toString();
    }

    /** Parses a full block-state string. Throws {@link CommandSyntaxException} on anything invalid. */
    public static BlockState parse(RegistryWrapper<Block> blockLookup, String input) throws CommandSyntaxException {
        return BlockArgumentParser.block(blockLookup, input, false).blockState();
    }

    /**
     * The state's properties as a name-sorted {@code name → value} string map (empty for stateless
     * blocks). Same string forms as {@link #serialize}; used by the structure exporter, whose NBT palette
     * stores name and properties separately.
     */
    public static Map<String, String> propertyMap(BlockState state) {
        if (state.getProperties().isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new TreeMap<>();
        for (Property<?> property : state.getProperties()) {
            map.put(property.getName(), valueName(state, property));
        }
        return map;
    }

    // Wildcard capture lets us read the property value generically without casts/suppression.
    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }
}
