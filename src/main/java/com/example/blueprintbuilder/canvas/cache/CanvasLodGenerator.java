package com.example.blueprintbuilder.canvas.cache;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the LOD pyramid for a Canvas Project with an <b>edge-preserving</b> 2×2 downsample tuned for pixel
 * art, so a huge image stays recognizable and sharp when viewed from far away instead of turning into a
 * blurry blob.
 *
 * <p>Per 2×2 block:</p>
 * <ul>
 *   <li>Transparent pixels (alpha 0, i.e. gaps) are ignored. If <i>any</i> source pixel is opaque the result
 *       is opaque, so thin features / single lines survive deep into the pyramid.</li>
 *   <li>If the opaque colours are nearly uniform (low variance) → average them (smooth gradients stay
 *       smooth). This is the only place simple averaging happens.</li>
 *   <li>Otherwise it's an <b>edge</b>: pick the majority colour (exact-match vote), or if all differ, the
 *       median-luminance colour. Either way an <i>existing</i> colour is chosen — never a muddy average — so
 *       contrast and edges are preserved.</li>
 * </ul>
 */
public final class CanvasLodGenerator {

    /** Max squared RGB distance from the block average below which a 2×2 block counts as "uniform". */
    private static final int UNIFORM_THRESHOLD = 1500;

    private CanvasLodGenerator() {}

    /**
     * @param base    full-resolution ARGB pixels (length {@code width*height})
     * @param maxExtraLevels how many levels beyond LOD 0 to attempt (e.g. 4 → LOD0..LOD4)
     * @return LODs starting with LOD 0 ({@code base}), each half the size of the previous, stopping early if
     *         a dimension would drop below 1.
     */
    public static List<CanvasLod> generate(int[] base, int width, int height, int maxExtraLevels) {
        List<CanvasLod> lods = new ArrayList<>();
        lods.add(new CanvasLod(0, width, height, base));

        int[] cur = base;
        int cw = width;
        int ch = height;
        for (int level = 1; level <= maxExtraLevels && cw > 1 && ch > 1; level++) {
            int nw = (cw + 1) / 2;
            int nh = (ch + 1) / 2;
            int[] next = downsample(cur, cw, ch, nw, nh);
            lods.add(new CanvasLod(level, nw, nh, next));
            cur = next;
            cw = nw;
            ch = nh;
        }
        return lods;
    }

    private static int[] downsample(int[] src, int sw, int sh, int dw, int dh) {
        int[] dst = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx = x * 2;
                int sy = y * 2;
                dst[y * dw + x] = combine(
                        at(src, sw, sh, sx, sy),
                        at(src, sw, sh, sx + 1, sy),
                        at(src, sw, sh, sx, sy + 1),
                        at(src, sw, sh, sx + 1, sy + 1));
            }
        }
        return dst;
    }

    private static int at(int[] src, int sw, int sh, int x, int y) {
        int cx = Math.min(x, sw - 1);
        int cy = Math.min(y, sh - 1);
        return src[cy * sw + cx];
    }

    /** Edge-preserving combine of up to four ARGB pixels. */
    static int combine(int a, int b, int c, int d) {
        int[] all = {a, b, c, d};
        int[] opaque = new int[4];
        int n = 0;
        for (int p : all) {
            if ((p >>> 24) != 0) {
                opaque[n++] = p;
            }
        }
        if (n == 0) {
            return 0; // fully transparent block
        }
        if (n == 1) {
            return 0xFF000000 | (opaque[0] & 0xFFFFFF);
        }

        // Average of the opaque colours, and the largest deviation from it.
        int sr = 0, sg = 0, sb = 0;
        for (int i = 0; i < n; i++) {
            sr += (opaque[i] >> 16) & 0xFF;
            sg += (opaque[i] >> 8) & 0xFF;
            sb += opaque[i] & 0xFF;
        }
        int ar = sr / n, ag = sg / n, ab = sb / n;
        int maxDist = 0;
        for (int i = 0; i < n; i++) {
            int dr = ((opaque[i] >> 16) & 0xFF) - ar;
            int dg = ((opaque[i] >> 8) & 0xFF) - ag;
            int db = (opaque[i] & 0xFF) - ab;
            maxDist = Math.max(maxDist, dr * dr + dg * dg + db * db);
        }

        if (maxDist <= UNIFORM_THRESHOLD) {
            return 0xFF000000 | (ar << 16) | (ag << 8) | ab; // smooth region → average
        }
        return 0xFF000000 | (edgeRepresentative(opaque, n) & 0xFFFFFF); // edge → an existing colour
    }

    /** Majority colour (exact match); if all distinct, the median-luminance colour. */
    private static int edgeRepresentative(int[] opaque, int n) {
        int bestColor = opaque[0];
        int bestCount = 0;
        for (int i = 0; i < n; i++) {
            int count = 0;
            for (int j = 0; j < n; j++) {
                if ((opaque[j] & 0xFFFFFF) == (opaque[i] & 0xFFFFFF)) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestColor = opaque[i];
            }
        }
        if (bestCount >= 2) {
            return bestColor;
        }
        // All distinct → median by luminance (robust to outliers, keeps the edge crisp).
        sortByLuminance(opaque, n);
        return opaque[n / 2];
    }

    private static void sortByLuminance(int[] a, int n) {
        for (int i = 1; i < n; i++) {
            int v = a[i];
            int lv = luminance(v);
            int j = i - 1;
            while (j >= 0 && luminance(a[j]) > lv) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
    }

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return 299 * r + 587 * g + 114 * b; // ×1000, ordering only
    }
}
