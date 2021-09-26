package me.salamander.why.debug.patterns;

import me.salamander.why.debug.LocalVariableMapper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class BlockPosUnpackingPattern implements BytecodePattern {
    private static final String OFFSET_0 = "net/minecraft/util/math/BlockPos#unpackLongX";
    private static final String OFFSET_1 = "net/minecraft/util/math/BlockPos#unpackLongY";
    private static final String OFFSET_2 = "net/minecraft/util/math/BlockPos#unpackLongZ";

    @Override
    public boolean apply(InsnList instructions, LocalVariableMapper variableMapper, int index) {
        if(index + 1 >= instructions.size()) return false;

        AbstractInsnNode first = instructions.get(index);
        AbstractInsnNode second = instructions.get(index + 1);

        if(first.getOpcode() != Opcodes.LLOAD) return false;
        VarInsnNode loadInstruction = (VarInsnNode) first;

        if(second.getOpcode() != Opcodes.INVOKESTATIC) return false;
        MethodInsnNode methodCall = (MethodInsnNode) second;

        if(!variableMapper.isARemappedTransformedLong(loadInstruction.var)) return false;

        String methodName = methodCall.owner + "#" + methodCall.name;

        if(methodName.equals(OFFSET_0)){
            loadInstruction.setOpcode(Opcodes.ILOAD);
            instructions.remove(second);
        }else if(methodName.equals(OFFSET_1)){
            loadInstruction.setOpcode(Opcodes.ILOAD);
            loadInstruction.var++;
            instructions.remove(second);
        }else if(methodName.equals(OFFSET_2)){
            loadInstruction.setOpcode(Opcodes.ILOAD);
            loadInstruction.var += 2;
            instructions.remove(second);
        }

        return false;
    }
}
