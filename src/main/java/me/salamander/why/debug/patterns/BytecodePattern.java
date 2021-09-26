package me.salamander.why.debug.patterns;

import me.salamander.why.debug.LocalVariableMapper;
import org.objectweb.asm.tree.InsnList;

public interface BytecodePattern {
    boolean apply(InsnList instructions, LocalVariableMapper variableMapper, int index);
}
