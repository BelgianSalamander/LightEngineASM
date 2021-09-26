package me.salamander.why.debug.patterns;

import me.salamander.why.debug.LocalVariableMapper;
import net.minecraft.world.chunk.light.ChunkBlockLightProvider;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.Map;

public class MaxPosExpansionPattern extends BytecodePackedUsePattern{
    protected MaxPosExpansionPattern(Map<String, String> transformedMethods) {
        super(transformedMethods);
    }

    @Override
    protected boolean matches(InsnList instructions, LocalVariableMapper mapper, int index) {
        if(instructions.get(index).getOpcode() != Opcodes.LDC) return false;

        LdcInsnNode loadNode = (LdcInsnNode) instructions.get(index);
        if(!(loadNode.cst instanceof Long)) return false;

        return ((Long) loadNode.cst) == Long.MAX_VALUE;
    }

    @Override
    protected int patternLength(InsnList instructions, LocalVariableMapper mapper, int index) {
        return 1;
    }

    @Override
    protected InsnList forX(InsnList instructions, LocalVariableMapper mapper, int index) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(Integer.MAX_VALUE));
        return code;
    }

    @Override
    protected InsnList forY(InsnList instructions, LocalVariableMapper mapper, int index) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(Integer.MAX_VALUE));
        return code;
    }

    @Override
    protected InsnList forZ(InsnList instructions, LocalVariableMapper mapper, int index) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(Integer.MAX_VALUE));
        return code;
    }
}
