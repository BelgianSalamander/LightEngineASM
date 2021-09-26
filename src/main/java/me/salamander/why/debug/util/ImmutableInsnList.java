package me.salamander.why.debug.util;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;

public class ImmutableInsnList {
    private final InsnList original;

    public ImmutableInsnList(InsnList insnList, ClassNode classNode){
        original = insnList;
    }

    public AbstractInsnNode get(int index){
        return original.get(index).clone(null);
    }
}
