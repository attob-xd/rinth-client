package rinth.fabric;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * Vertices copied from one format to another, element by element: what a built-in Fabric mod's
 * vertex serializers do (ADR 0065). Sodium makes each serializer as a class at run time, which the
 * browser can't load, so tools/fabricmods has its serializers call this instead, with the same
 * copies: each of the target format's elements from the source's element of the same name.
 *
 * <p>Hot path (Sodium's text and models): {@link #copy} allocates nothing.
 */
public final class VertexCopies {

    private VertexCopies() {}

    /**
     * The copies from {@code source} to {@code target}: the two vertex sizes, then each copy's
     * source offset, target offset and length, adjacent copies merged.
     *
     * @throws IllegalArgumentException if {@code source} lacks an element {@code target} has
     */
    public static int[] plan(VertexFormat source, VertexFormat target) {
        List<int[]> copies = new ArrayList<>();
        for (VertexFormatElement element : target.getElements()) {
            VertexFormatElement from = source.getElement(element.name());
            if (from == null) {
                throw new IllegalArgumentException(
                        "The source format " + source + " has no " + element.name() + " for " + target);
            }
            int length = element.format().blockSize();
            int[] last = copies.isEmpty() ? null : copies.getLast();
            if (last != null && last[0] + last[2] == from.offset() && last[1] + last[2] == element.offset()) {
                last[2] += length;
            } else {
                copies.add(new int[] {from.offset(), element.offset(), length});
            }
        }
        int[] plan = new int[2 + copies.size() * 3];
        plan[0] = source.getVertexSize();
        plan[1] = target.getVertexSize();
        for (int i = 0; i < copies.size(); i++) {
            System.arraycopy(copies.get(i), 0, plan, 2 + i * 3, 3);
        }
        return plan;
    }

    /** Copies {@code count} vertices from {@code source} to {@code target} as {@link #plan} says. */
    public static void copy(int[] plan, long source, long target, int count) {
        int sourceSize = plan[0];
        int targetSize = plan[1];
        for (int vertex = 0; vertex < count; vertex++) {
            long from = source + (long) vertex * sourceSize;
            long to = target + (long) vertex * targetSize;
            for (int i = 2; i < plan.length; i += 3) {
                MemoryUtil.memCopy(from + plan[i], to + plan[i + 1], plan[i + 2]);
            }
        }
    }
}
