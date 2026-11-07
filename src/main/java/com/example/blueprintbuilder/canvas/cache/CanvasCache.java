package com.example.blueprintbuilder.canvas.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * On-disk persistence for {@link CanvasProject}s. <b>I/O only</b> — pure Java, no Minecraft types — so it
 * unit-tests with a round-trip. One gzipped binary file per project ({@code .ccp}); the pixel arrays are
 * written as raw bytes for speed.
 */
public final class CanvasCache {

    private static final int MAGIC = 0x43414331; // "CAC1"
    private static final int VERSION = 1;
    private static final String EXT = ".ccp";

    /** int↔byte conversion buffer size (multiple of 4) — keeps peak memory flat for huge pixel arrays. */
    private static final int CHUNK_BYTES = 32 * 1024;

    /** Upper bound on lods-per-file / pixels-per-LOD a well-formed file can contain (corruption guard). */
    private static final int MAX_LODS = 32;
    private static final long MAX_PIXELS = 256L * 1024 * 1024; // 256M pixels = 1 GiB — far beyond any real canvas

    private CanvasCache() {}

    public static String fileName(String projectId) {
        StringBuilder sb = new StringBuilder(projectId.length() + EXT.length());
        for (int i = 0; i < projectId.length(); i++) {
            char c = projectId.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == '@' ? c : '_');
        }
        return sb.append(EXT).toString();
    }

    public static void write(Path file, CanvasProject project) throws IOException {
        try (OutputStream raw = Files.newOutputStream(file);
             OutputStream gz = new GZIPOutputStream(new BufferedOutputStream(raw));
             DataOutputStream out = new DataOutputStream(gz)) {

            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(project.id());
            out.writeUTF(project.dimensionId());
            out.writeInt(project.plane().ordinal());
            out.writeInt(project.originX());
            out.writeInt(project.originY());
            out.writeInt(project.originZ());
            out.writeInt(project.width());
            out.writeInt(project.height());
            out.writeInt(project.depth());
            out.writeLong(project.contentHash());

            List<CanvasLod> lods = project.lods();
            out.writeInt(lods.size());
            byte[] chunk = new byte[CHUNK_BYTES];
            for (CanvasLod lod : lods) {
                out.writeInt(lod.level());
                out.writeInt(lod.width());
                out.writeInt(lod.height());
                int[] px = lod.pixels();
                out.writeInt(px.length * 4);
                // Convert through a small reusable buffer instead of one full-size byte[] copy
                // (a 2000×2000 LOD0 would otherwise need a 16 MB temporary).
                for (int off = 0; off < px.length; ) {
                    int n = Math.min(px.length - off, CHUNK_BYTES / 4);
                    ByteBuffer.wrap(chunk, 0, n * 4).asIntBuffer().put(px, off, n);
                    out.write(chunk, 0, n * 4);
                    off += n;
                }
            }
        }
    }

    public static CanvasProject read(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             InputStream gz = new GZIPInputStream(new BufferedInputStream(raw));
             DataInputStream in = new DataInputStream(gz)) {

            if (in.readInt() != MAGIC) {
                throw new IOException("Not a canvas cache file: " + file.getFileName());
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported canvas cache version " + version);
            }
            String id = in.readUTF();
            String dimensionId = in.readUTF();
            int planeOrdinal = in.readInt();
            if (planeOrdinal < 0 || planeOrdinal >= CanvasPlane.values().length) {
                throw new IOException("Corrupted canvas cache file (bad plane): " + file.getFileName());
            }
            CanvasPlane plane = CanvasPlane.values()[planeOrdinal];
            int ox = in.readInt(), oy = in.readInt(), oz = in.readInt();
            int width = in.readInt(), height = in.readInt(), depth = in.readInt();
            long contentHash = in.readLong();

            int lodCount = in.readInt();
            // Every field below sizes an allocation or a loop, so reject nonsense values instead of
            // letting a corrupted file throw OOME/NegativeArraySize (which would escape the loader's
            // IOException handling and crash world load).
            if (width <= 0 || height <= 0 || depth <= 0 || lodCount <= 0 || lodCount > MAX_LODS) {
                throw new IOException("Corrupted canvas cache file (bad dimensions): " + file.getFileName());
            }
            List<CanvasLod> lods = new ArrayList<>(lodCount);
            byte[] chunk = new byte[CHUNK_BYTES];
            for (int i = 0; i < lodCount; i++) {
                int level = in.readInt();
                int w = in.readInt();
                int h = in.readInt();
                int byteLen = in.readInt();
                if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS || byteLen != w * h * 4) {
                    throw new IOException("Corrupted canvas cache file (bad LOD " + i + "): " + file.getFileName());
                }
                // Fill the pixel array through a small reusable buffer — no full-size byte[] duplicate.
                int[] px = new int[w * h];
                for (int off = 0; off < px.length; ) {
                    int n = Math.min(px.length - off, CHUNK_BYTES / 4);
                    in.readFully(chunk, 0, n * 4);
                    ByteBuffer.wrap(chunk, 0, n * 4).asIntBuffer().get(px, off, n);
                    off += n;
                }
                lods.add(new CanvasLod(level, w, h, px));
            }
            return new CanvasProject(id, dimensionId, plane, ox, oy, oz, width, height, depth, contentHash, lods);
        }
    }
}
