package me.salamander.why.v2.patterns2;

import me.salamander.why.debug.LocalVariableMapper;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Map;

public class BlockPosOffsetPattern extends BytecodePackedUsePattern {
    @Override
    public boolean matches(InsnList instructions, LocalVariableMapper mapper, int index) {
        if(instructions.size() <= index + 2) return false;

        if(instructions.get(index).getOpcode() == Opcodes.LLOAD){
            if(!mapper.isARemappedTransformedLong(((VarInsnNode) instructions.get(index)).var)){
                return false;
            }
        }else{
            return false;
        }

        if(!(instructions.get(index + 1).getOpcode() == Opcodes.ALOAD)){
            return false;
        }

        if(instructions.get(index + 2) instanceof MethodInsnNode methodCall){
            return methodCall.owner.equals("net/minecraft/util/math/BlockPos") && methodCall.name.equals("offset");
        }

        return false;
    }

    @Override
    public int patternLength(InsnList instructions, LocalVariableMapper mapper, int index) {
        return 3;
    }

    @Override
    public InsnList forX(InsnList instructions, LocalVariableMapper mapper, int index) {
        InsnList xCode = new InsnList();
        xCode.add(new VarInsnNode(Opcodes.ILOAD, getLongIndex(instructions, index)));
        xCode.add(new VarInsnNode(Opcodes.ALOAD, getDirectionIndex(instructions, index)));
        xCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/util/math/Direction", "getOffsetX", "()I"));
        xCode.add(new InsnNode(Opcodes.IADD));

        return xCode;
    }

    @Override
    public InsnList forY(InsnList instructions, LocalVariableMapper mapper, int index) {
        InsnList yCode = new InsnList();
        yCode.add(new VarInsnNode(Opcodes.ILOAD, getLongIndex(instructions, index) + 1));
        yCode.add(new VarInsnNode(Opcodes.ALOAD, getDirectionIndex(instructions, index)));
        yCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/util/math/Direction", "getOffsetY", "()I"));
        yCode.add(new InsnNode(Opcodes.IADD));

        return yCode;
    }

    @Override
    public InsnList forZ(InsnList instructions, LocalVariableMapper mapper, int index) {
        InsnList zCode = new InsnList();
        zCode.add(new VarInsnNode(Opcodes.ILOAD, getLongIndex(instructions, index) + 2));
        zCode.add(new VarInsnNode(Opcodes.ALOAD, getDirectionIndex(instructions, index)));
        zCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/util/math/Direction", "getOffsetZ", "()I"));
        zCode.add(new InsnNode(Opcodes.IADD));

        return zCode;
    }

    private int getLongIndex(InsnList instructions, int index){
        return ((VarInsnNode) instructions.get(index)).var;
    }

    private int getDirectionIndex(InsnList instruction, int index){
        return ((VarInsnNode) instruction.get(index + 1)).var;
    }
}
