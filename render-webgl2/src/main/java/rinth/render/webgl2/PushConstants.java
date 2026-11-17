package rinth.render.webgl2;

import static org.teavm.jso.webgl.WebGL2RenderingContext.DYNAMIC_DRAW;
import static org.teavm.jso.webgl.WebGL2RenderingContext.UNIFORM_BUFFER;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.webgl.WebGLBuffer;
import rinth.render.webgl2.shaders.TranslatedStage;

/**
 * Push constants, which WebGL2 lacks, as the uniform block the build translates them to
 * ({@link TranslatedStage#PUSH_CONSTANTS}). Only a built-in Fabric mod's pipelines have them
 * (Sodium's terrain pushes each region's offset). What a pass pushes is uploaded to a ring of
 * uniform buffer space before the next draw that reads it, and bound as the block; each push has
 * its own place in the ring, so draws already issued keep what was pushed for them.
 *
 * <p>Hot path (a push per terrain region): no allocation.
 */
final class PushConstants {

    /** The most a pipeline may push, as the game's pipeline builder allows. */
    static final int MAX_BYTES = 128;

    private static final int RING_BYTES = 64 * 1024;

    private final WebGl2Device device;
    private final WebGLBuffer ring;
    private final int binding;
    private final int alignment;
    private final ByteBuffer pushed = ByteBuffer.allocateDirect(MAX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private boolean dirty;
    private int next;
    /** A kept view of {@link #pushed}, remade when detached. */
    private @Nullable Int8Array view;

    /**
     * @param binding the uniform buffer binding point the block is bound at, which no pipeline's
     *     own uniforms use
     * @param alignment {@code UNIFORM_BUFFER_OFFSET_ALIGNMENT}
     */
    PushConstants(WebGl2Device device, int binding, int alignment) {
        this.device = device;
        this.binding = binding;
        this.alignment = Math.max(4, alignment);
        this.ring = device.gl().createBuffer();
        device.gl().bindBuffer(UNIFORM_BUFFER, ring);
        device.gl().bufferData(UNIFORM_BUFFER, RING_BYTES + MAX_BYTES, DYNAMIC_DRAW);
    }

    /** The binding point the block is bound at. */
    int binding() {
        return binding;
    }

    /** Takes {@code value}'s remaining bytes (at most {@link #MAX_BYTES}) for the next draw; the rest reads zero. */
    void push(ByteBuffer value) {
        int length = Math.min(value.remaining(), MAX_BYTES);
        int start = value.position();
        for (int i = 0; i < MAX_BYTES; i++) {
            pushed.put(i, i < length ? value.get(start + i) : 0);
        }
        dirty = true;
    }

    /** Uploads what was pushed since the last draw, if anything, and binds it as the block. */
    void bindForDraw() {
        if (!dirty) {
            return;
        }
        dirty = false;
        if (next + MAX_BYTES > RING_BYTES) {
            next = 0;
        }
        device.gl().bindBuffer(UNIFORM_BUFFER, ring);
        Int8Array kept = view;
        if (kept == null || !WebGl.bufferSubData(device.gl(), UNIFORM_BUFFER, next, kept, MAX_BYTES)) {
            kept = Int8Array.fromJavaBuffer(pushed);
            view = kept;
            WebGl.bufferSubData(device.gl(), UNIFORM_BUFFER, next, kept, MAX_BYTES);
        }
        device.gl().bindBufferRange(UNIFORM_BUFFER, binding, ring, next, MAX_BYTES);
        next += (MAX_BYTES + alignment - 1) / alignment * alignment;
    }
}
