package me.salamander.why.v2.patterns2;

import me.salamander.why.debug.LocalVariableMapper;
import me.salamander.why.debug.OpcodeUtil;
import me.salamander.why.debug.patterns.BytecodePattern;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Map;

public abstract class BytecodePackedUsePattern implements BytecodePattern {
    @Override
    public boolean apply(InsnList instructions, LocalVariableMapper variableMapper, int index) {
        if(matches(instructions, variableMapper, index)){
            int patternLength = patternLength(instructions, variableMapper, index);
            int searchIndex = index + patternLength;
            int syntheticStackSize = 1;
            while (true){
                int consumed = OpcodeUtil.getConsumedOperands(instructions.get(searchIndex));
                int change = OpcodeUtil.getStackChange(instructions.get(searchIndex));
                if(consumed >= syntheticStackSize) break;
                syntheticStackSize += change;
                if(syntheticStackSize <= 0) break;
                searchIndex++;
            }
            AbstractInsnNode consumerInstruction = instructions.get(searchIndex);
            System.out.println("Consumer: " + consumerInstruction);
            if(consumerInstruction.getOpcode() == Opcodes.LSTORE && searchIndex - patternLength == index){
                int localVar = ((VarInsnNode) consumerInstruction).var;
                InsnList newInstructions = new InsnList();

                newInstructions.add(forX(instructions, variableMapper, index));
                newInstructions.add(new VarInsnNode(Opcodes.ISTORE, localVar));

                newInstructions.add(forY(instructions, variableMapper, index));
                newInstructions.add(new VarInsnNode(Opcodes.ISTORE, localVar + 1));

                newInstructions.add(forZ(instructions, variableMapper, index));
                newInstructions.add(new VarInsnNode(Opcodes.ISTORE, localVar + 2));

                for(int i = 0; i < patternLength + 1; i++){
                    instructions.remove(instructions.get(index));
                }
                instructions.insertBefore(instructions.get(index), newInstructions);
            } else{
                throw new IllegalStateException("Unsupported Pattern Usage!");
            }

            return true;
        }
        return false;
    }

    protected abstract boolean matches(InsnList instructions, LocalVariableMapper mapper, int index);
    protected abstract int patternLength(InsnList instructions, LocalVariableMapper mapper, int index);

    protected abstract InsnList forX(InsnList instructions, LocalVariableMapper mapper, int index);
    protected abstract InsnList forY(InsnList instructions, LocalVariableMapper mapper, int index);
    protected abstract InsnList forZ(InsnList instructions, LocalVariableMapper mapper, int index);
}
