package me.salamander.why.v2;

import me.salamander.why.debug.OpcodeUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public class BasicSourceInterpreter extends Interpreter<BasicSourceValue> {
    private final Map<BasicSourceValue, Set<AbstractInsnNode>> consumers = new HashMap<>();

    protected BasicSourceInterpreter(int api) {
        super(api);
    }

    @Override
    public BasicSourceValue newValue(Type type) {
        if(type == null){
            return new BasicSourceValue(null);
        }
        if(type.getSort() == Type.VOID) return null;
        if(type.getSort() == Type.METHOD) throw new AssertionError();
        return new BasicSourceValue(type);
    }

    @Override
    public BasicSourceValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
        if(type == Type.VOID_TYPE) return null;
        return new BasicSourceValue(type, local);
    }

    @Override
    public BasicSourceValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
        return switch (insn.getOpcode()){
            case Opcodes.ACONST_NULL -> new BasicSourceValue(BasicInterpreter.NULL_TYPE, insn);
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
                    Opcodes.ICONST_4, Opcodes.ICONST_5 -> new BasicSourceValue(Type.INT_TYPE, insn);
            case Opcodes.LCONST_0, Opcodes.LCONST_1 -> new BasicSourceValue(Type.LONG_TYPE, insn);
            case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> new BasicSourceValue(Type.FLOAT_TYPE, insn);
            case Opcodes.DCONST_0, Opcodes.DCONST_1 -> new BasicSourceValue(Type.DOUBLE_TYPE, insn);
            case Opcodes.BIPUSH -> new BasicSourceValue(Type.BYTE_TYPE, insn);
            case Opcodes.SIPUSH -> new BasicSourceValue(Type.SHORT_TYPE, insn);
            case Opcodes.LDC -> {
                Object value = ((LdcInsnNode) insn).cst;
                if (value instanceof Integer) {
                    yield  new BasicSourceValue(Type.INT_TYPE, insn);
                } else if (value instanceof Float) {
                    yield  new BasicSourceValue(Type.FLOAT_TYPE, insn);
                } else if (value instanceof Long) {
                    yield  new BasicSourceValue(Type.LONG_TYPE, insn);
                } else if (value instanceof Double) {
                    yield  new BasicSourceValue(Type.DOUBLE_TYPE, insn);
                } else if (value instanceof String) {
                    yield  new BasicSourceValue(Type.getObjectType("java/lang/String"), insn);
                } else if (value instanceof Type) {
                    int sort = ((Type) value).getSort();
                    if (sort == Type.OBJECT || sort == Type.ARRAY) {
                        yield new BasicSourceValue(Type.getObjectType("java/lang/Class"), insn);
                    } else if (sort == Type.METHOD) {
                        yield new BasicSourceValue(Type.getObjectType("java/lang/invoke/MethodType"), insn);
                    } else {
                        throw new AnalyzerException(insn, "Illegal LDC value " + value);
                    }
                }
                throw new IllegalStateException("This shouldn't happen");
            }
            case Opcodes.JSR -> new BasicSourceValue(Type.VOID_TYPE, insn);
            case Opcodes.GETSTATIC -> new BasicSourceValue(Type.getType(((FieldInsnNode) insn).desc), insn);
            case Opcodes.NEW -> new BasicSourceValue(Type.getObjectType(((TypeInsnNode) insn).desc), insn);
            default -> throw new IllegalStateException("Unexpected value: " + insn.getType());
        };
    }

    @Override
    public BasicSourceValue copyOperation(AbstractInsnNode insn, BasicSourceValue value) throws AnalyzerException {
        if(insn instanceof VarInsnNode varInsn){
            if(OpcodeUtil.isLocalVarStore(varInsn.getOpcode())){
                consumeBy(value, insn);
            }
            return new BasicSourceValue(value.getType(), insn, varInsn.var);
        }
        return value;
    }

    @Override
    public BasicSourceValue unaryOperation(AbstractInsnNode insn, BasicSourceValue value) throws AnalyzerException {
        consumeBy(value, insn);

        switch (insn.getOpcode()) {
            case INEG:
            case IINC:
            case L2I:
            case F2I:
            case D2I:
            case I2B:
            case I2C:
            case I2S:
                return new BasicSourceValue(Type.INT_TYPE, insn);
            case FNEG:
            case I2F:
            case L2F:
            case D2F:
                return new BasicSourceValue(Type.FLOAT_TYPE, insn);
            case LNEG:
            case I2L:
            case F2L:
            case D2L:
                return new BasicSourceValue(Type.LONG_TYPE, insn);
            case DNEG:
            case I2D:
            case L2D:
            case F2D:
                return new BasicSourceValue(Type.DOUBLE_TYPE, insn);
            case IFEQ:
            case IFNE:
            case IFLT:
            case IFGE:
            case IFGT:
            case IFLE:
            case TABLESWITCH:
            case LOOKUPSWITCH:
            case IRETURN:
            case LRETURN:
            case FRETURN:
            case DRETURN:
            case ARETURN:
            case PUTSTATIC:
                return null;
            case GETFIELD:
                return new BasicSourceValue(Type.getType(((FieldInsnNode) insn).desc), insn);
            case NEWARRAY:
                switch (((IntInsnNode) insn).operand) {
                    case T_BOOLEAN:
                        return new BasicSourceValue(Type.getType("[Z"), insn);
                    case T_CHAR:
                        return new BasicSourceValue(Type.getType("[C"), insn);
                    case T_BYTE:
                        return new BasicSourceValue(Type.getType("[B"), insn);
                    case T_SHORT:
                        return new BasicSourceValue(Type.getType("[S"), insn);
                    case T_INT:
                        return new BasicSourceValue(Type.getType("[I"), insn);
                    case T_FLOAT:
                        return new BasicSourceValue(Type.getType("[F"), insn);
                    case T_DOUBLE:
                        return new BasicSourceValue(Type.getType("[D"), insn);
                    case T_LONG:
                        return new BasicSourceValue(Type.getType("[J"), insn);
                    default:
                        break;
                }
                throw new AnalyzerException(insn, "Invalid array type");
            case ANEWARRAY:
                return new BasicSourceValue(Type.getType("[" + Type.getObjectType(((TypeInsnNode) insn).desc)), insn);
            case ARRAYLENGTH:
                return new BasicSourceValue(Type.INT_TYPE, insn);
            case ATHROW:
                return null;
            case CHECKCAST:
                return new BasicSourceValue(Type.getObjectType(((TypeInsnNode) insn).desc), insn);
            case INSTANCEOF:
                return new BasicSourceValue(Type.INT_TYPE, insn);
            case MONITORENTER:
            case MONITOREXIT:
            case IFNULL:
            case IFNONNULL:
                return null;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public BasicSourceValue binaryOperation(AbstractInsnNode insn, BasicSourceValue value1, BasicSourceValue value2) throws AnalyzerException {
        consumeBy(value1, insn);
        consumeBy(value2, insn);
        switch (insn.getOpcode()) {
            case IALOAD:
            case BALOAD:
            case CALOAD:
            case SALOAD:
            case IADD:
            case ISUB:
            case IMUL:
            case IDIV:
            case IREM:
            case ISHL:
            case ISHR:
            case IUSHR:
            case IAND:
            case IOR:
            case IXOR:
                return new BasicSourceValue(Type.INT_TYPE, insn);
            case FALOAD:
            case FADD:
            case FSUB:
            case FMUL:
            case FDIV:
            case FREM:
                return new BasicSourceValue(Type.FLOAT_TYPE, insn);
            case LALOAD:
            case LADD:
            case LSUB:
            case LMUL:
            case LDIV:
            case LREM:
            case LSHL:
            case LSHR:
            case LUSHR:
            case LAND:
            case LOR:
            case LXOR:
                return new BasicSourceValue(Type.LONG_TYPE, insn);
            case DALOAD:
            case DADD:
            case DSUB:
            case DMUL:
            case DDIV:
            case DREM:
                return new BasicSourceValue(Type.DOUBLE_TYPE, insn);
            case AALOAD:
                return new BasicSourceValue(Type.getObjectType("java/lang/Object"), insn);
            case LCMP:
            case FCMPL:
            case FCMPG:
            case DCMPL:
            case DCMPG:
                return new BasicSourceValue(Type.INT_TYPE, insn);
            case IF_ICMPEQ:
            case IF_ICMPNE:
            case IF_ICMPLT:
            case IF_ICMPGE:
            case IF_ICMPGT:
            case IF_ICMPLE:
            case IF_ACMPEQ:
            case IF_ACMPNE:
            case PUTFIELD:
                return null;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public BasicSourceValue ternaryOperation(AbstractInsnNode insn, BasicSourceValue value1, BasicSourceValue value2, BasicSourceValue value3) throws AnalyzerException {
        consumeBy(value1, insn);
        consumeBy(value2, insn);
        consumeBy(value3, insn);
        return null;
    }

    @Override
    public BasicSourceValue naryOperation(AbstractInsnNode insn, List<? extends BasicSourceValue> values) throws AnalyzerException {
        for(BasicSourceValue value : values){
            consumeBy(value, insn);
        }

        int opcode = insn.getOpcode();
        if (opcode == MULTIANEWARRAY) {
            return new BasicSourceValue(Type.getType(((MultiANewArrayInsnNode) insn).desc), insn);
        } else if (opcode == INVOKEDYNAMIC) {
            Type type = Type.getReturnType(((InvokeDynamicInsnNode) insn).desc);
            if(type.getSort() == Type.VOID) return null;
            return new BasicSourceValue(type, insn);
        } else {
            Type type = Type.getReturnType(((MethodInsnNode) insn).desc);
            if(type.getSort() == Type.VOID) return null;
            return new BasicSourceValue(type, insn);
        }
    }

    @Override
    public void returnOperation(AbstractInsnNode insn, BasicSourceValue value, BasicSourceValue expected) throws AnalyzerException {
        consumeBy(value, insn);
    }

    @Override
    public BasicSourceValue merge(BasicSourceValue value1, BasicSourceValue value2) {
        return value1.merge(value2);
    }

    private void consumeBy(BasicSourceValue value, AbstractInsnNode consumer){
        assert value != null;
        consumers.computeIfAbsent(value, key -> new HashSet<>(2)).add(consumer);
    }

    public Set<AbstractInsnNode> getConsumersFor(BasicSourceValue value){
        assert value != null;
        return consumers.get(value);
    }

    public Map<BasicSourceValue, Set<AbstractInsnNode>> getConsumers() {
        return consumers;
    }

    public void clearCache() {
        consumers.clear();
    }
}
