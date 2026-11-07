package com.example.blueprintbuilder.canvas;

import com.example.blueprintbuilder.BlueprintBuilderMod;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * Registration hub for the Canvas Block, its item and its block-entity type. Kept separate from the block,
 * builder and CSV logic so each has a single responsibility.
 */
public final class CanvasRegistry {

    public static final Identifier CANVAS_ID = Identifier.of(BlueprintBuilderMod.MOD_ID, "canvas_block");

    public static final CanvasBlock CANVAS_BLOCK = new CanvasBlock(
            AbstractBlock.Settings.create()
                    .strength(0.8f)
                    .sounds(BlockSoundGroup.WOOL));

    public static final BlockItem CANVAS_BLOCK_ITEM = new BlockItem(CANVAS_BLOCK, new Item.Settings());

    // Assigned in register(); only dereferenced when block entities are created (after registration).
    public static BlockEntityType<CanvasBlockEntity> CANVAS_BLOCK_ENTITY;

    private CanvasRegistry() {}

    // FabricBlockEntityTypeBuilder is deprecated in favour of vanilla BlockEntityType.Builder, but the
    // vanilla path requires an access widener (BlockEntityType.BlockEntityFactory is package-private).
    // Using the Fabric builder keeps this registration access-widener-free, so the mod compiles in any
    // workspace regardless of whether Fabric API's access widener is applied to the compile classpath.
    @SuppressWarnings("deprecation")
    public static void register() {
        Registry.register(Registries.BLOCK, CANVAS_ID, CANVAS_BLOCK);
        Registry.register(Registries.ITEM, CANVAS_ID, CANVAS_BLOCK_ITEM);

        CANVAS_BLOCK_ENTITY = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                CANVAS_ID,
                FabricBlockEntityTypeBuilder.create(CanvasBlockEntity::new, CANVAS_BLOCK).build());

        // Add to the vanilla "Building Blocks" creative tab by its registry key (the ItemGroups.BUILDING_BLOCKS
        // field is private without an access widener).
        RegistryKey<ItemGroup> buildingBlocks =
                RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of("minecraft", "building_blocks"));
        ItemGroupEvents.modifyEntriesEvent(buildingBlocks).register(entries -> entries.add(CANVAS_BLOCK));

        BlueprintBuilderMod.LOGGER.info("[Canvas] Registered canvas block + block entity.");
    }
}
