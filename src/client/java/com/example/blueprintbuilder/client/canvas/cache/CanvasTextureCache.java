package com.example.blueprintbuilder.client.canvas.cache;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.example.blueprintbuilder.BlueprintBuilderMod;
import com.example.blueprintbuilder.canvas.cache.CanvasLod;
import com.example.blueprintbuilder.canvas.cache.CanvasProject;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * Uploads a Canvas Project LOD's pixels to a single GPU texture and caches it, keyed by project + LOD. One
 * texture per visible (project, LOD) — this is what lets the renderer draw a whole image as one quad instead
 * of millions of cubes. Re-uploads automatically when a project's content hash changes; closes textures when
 * cleared.
 */
public final class CanvasTextureCache {

    private record Entry(long contentHash, Identifier id) {}

    private static final Map<String, Entry> TEXTURES = new HashMap<>();

    private CanvasTextureCache() {}

    /** Returns a registered texture id for {@code project}'s {@code lodLevel}, creating/refreshing it lazily. */
    public static Identifier getOrCreate(CanvasProject project, int lodLevel) {
        String key = project.id() + "#" + lodLevel;
        Entry entry = TEXTURES.get(key);
        if (entry != null && entry.contentHash() == project.contentHash()) {
            return entry.id();
        }
        if (entry != null) {
            destroy(entry.id()); // stale (project changed) → replace
        }

        CanvasLod lod = project.lod(lodLevel);
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, lod.width(), lod.height(), false);
        int[] px = lod.pixels();
        for (int y = 0; y < lod.height(); y++) {
            for (int x = 0; x < lod.width(); x++) {
                image.setColor(x, y, argbToAbgr(px[y * lod.width() + x]));
            }
        }
        NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
        texture.setFilter(false, false); // NEAREST, no mipmap → crisp pixel art at distance
        texture.upload();                // push pixels to the GPU now (we're on the render thread)

        Identifier id = Identifier.of(BlueprintBuilderMod.MOD_ID, "canvas_cache/" + sanitize(key));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        TEXTURES.put(key, new Entry(project.contentHash(), id));
        return id;
    }

    /** Destroys every cached texture (call on disconnect / cache clear). Cheap no-op when empty. */
    public static void clearAll() {
        if (TEXTURES.isEmpty()) {
            return; // also the per-frame fast path while no projects exist
        }
        for (Iterator<Map.Entry<String, Entry>> it = TEXTURES.entrySet().iterator(); it.hasNext(); ) {
            destroy(it.next().getValue().id());
            it.remove();
        }
    }

    private static void destroy(Identifier id) {
        MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
    }

    /** NativeImage (RGBA) stores pixels as 0xAABBGGRR; our project pixels are 0xAARRGGBB — swap R and B. */
    private static int argbToAbgr(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static String sanitize(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = Character.toLowerCase(key.charAt(i));
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' ? c : '_');
        }
        return sb.toString();
    }
}
