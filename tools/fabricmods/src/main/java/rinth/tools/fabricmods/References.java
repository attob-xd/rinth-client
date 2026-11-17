package rinth.tools.fabricmods;

import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** The classes a class names anywhere: its supertypes, fields, methods, code and annotations. */
final class References {

    private References() {}

    /** Internal names, arrays' element types included; primitives aren't names. */
    static Set<String> of(byte[] bytes) {
        Set<String> names = new TreeSet<>();
        Remapper collector = new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                if (!internalName.startsWith("[")) {
                    names.add(internalName);
                }
                return internalName;
            }
        };
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), collector), 0);
        return names;
    }
}
