package rinth.tools.fabricmods;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

class AccessWidenersTest {

    private static byte[] gameClass(@Nullable String signature) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, 0, "game/Thing", signature, "java/lang/Object", new String[] {"game/Old"});
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    @Test
    void readsClassTweakers() {
        AccessWideners wideners = new AccessWideners();
        Report report = new Report();
        wideners.add("mod", """
                classTweaker\tv1\tofficial
                accessible\tclass\tgame/Thing
                transitive-inject-interface\tgame/Thing\tmod/Extra<Ljava/lang/String;>
                """.getBytes(StandardCharsets.UTF_8), report);

        assertThat(report.problems()).isZero();
        assertThat(wideners.owners()).containsExactly("game/Thing");
        byte[] widened = wideners.apply("game/Thing", gameClass("<T:Ljava/lang/Object;>Ljava/lang/Object;Lgame/Old;"));
        assertThat(widened).isNotNull();
        ClassNode node = read(widened);
        assertThat(node.access & Opcodes.ACC_PUBLIC).isNotZero();
        assertThat(node.interfaces).containsExactly("game/Old", "mod/Extra");
        assertThat(node.signature)
                .isEqualTo("<T:Ljava/lang/Object;>Ljava/lang/Object;Lgame/Old;Lmod/Extra<Ljava/lang/String;>;");
    }

    @Test
    void stillReadsAccessWideners() {
        AccessWideners wideners = new AccessWideners();
        Report report = new Report();
        wideners.add(
                "mod",
                "accessWidener v2 named\n# a comment\nextendable class game/Thing\n".getBytes(StandardCharsets.UTF_8),
                report);

        assertThat(report.problems()).isZero();
        byte[] widened = wideners.apply("game/Thing", gameClass(null));
        assertThat(widened).isNotNull();
        assertThat(read(widened).access & Opcodes.ACC_FINAL).isZero();
    }

    @Test
    void skipsAFileWithNoHeader() {
        AccessWideners wideners = new AccessWideners();
        Report report = new Report();
        wideners.add("mod", "accessible class game/Thing\n".getBytes(StandardCharsets.UTF_8), report);

        assertThat(report.problems()).isEqualTo(1);
        assertThat(wideners.owners()).isEmpty();
    }
}
