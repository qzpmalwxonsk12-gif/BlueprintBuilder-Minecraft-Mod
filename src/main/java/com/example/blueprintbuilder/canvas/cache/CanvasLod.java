package com.example.blueprintbuilder.canvas.cache;

/**
 * One Level of Detail: a {@code width × height} grid of packed ARGB pixels (0xAARRGGBB; alpha 0 = a gap /
 * absent block). LOD 0 is full resolution; each subsequent level halves the dimensions.
 *
 * <p>Pure data — no Minecraft types — so it serializes and unit-tests trivially.</p>
 */
public record CanvasLod(int level, int width, int height, int[] pixels) {

    public int pixelCount() {
        return width * height;
    }

    public int get(int x, int y) {
        return pixels[y * width + x];
    }
}
