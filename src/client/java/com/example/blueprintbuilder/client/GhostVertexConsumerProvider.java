package com.example.blueprintbuilder.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;

/**
 * A {@link VertexConsumerProvider} that funnels every render layer into one translucent layer and forces a
 * low alpha on all vertex colors. Wrapping the world renderer's buffers like this lets us reuse
 * {@code BlockRenderManager.renderBlockAsEntity(...)} to draw a normal block model as a translucent
 * "ghost" without writing custom model code.
 */
public final class GhostVertexConsumerProvider implements VertexConsumerProvider {

    private final VertexConsumerProvider delegate;
    private final RenderLayer translucentLayer;
    private final int maxAlpha; // 0..255

    // getBuffer is called for every ghost block model; reuse one wrapper as long as the delegate keeps
    // returning the same underlying buffer (an Immediate does within a frame) instead of allocating
    // thousands of identical wrappers per frame.
    private VertexConsumer cachedDelegate;
    private VertexConsumer cachedWrapper;

    public GhostVertexConsumerProvider(VertexConsumerProvider delegate, RenderLayer translucentLayer, float alpha) {
        this.delegate = delegate;
        this.translucentLayer = translucentLayer;
        this.maxAlpha = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
    }

    @Override
    public VertexConsumer getBuffer(RenderLayer layer) {
        // Ignore the requested (often opaque) layer; always draw into the translucent one so alpha blends.
        VertexConsumer buffer = delegate.getBuffer(translucentLayer);
        if (buffer != cachedDelegate) {
            cachedDelegate = buffer;
            cachedWrapper = new AlphaForcingVertexConsumer(buffer, maxAlpha);
        }
        return cachedWrapper;
    }

    /** Delegates everything to a wrapped consumer but clamps the alpha channel of every color. */
    private static final class AlphaForcingVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final int maxAlpha;

        AlphaForcingVertexConsumer(VertexConsumer delegate, int maxAlpha) {
            this.delegate = delegate;
            this.maxAlpha = maxAlpha;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            delegate.color(red, green, blue, Math.min(alpha, maxAlpha));
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            delegate.texture(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            delegate.overlay(u, v);
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            delegate.light(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            delegate.normal(x, y, z);
            return this;
        }
    }
}
