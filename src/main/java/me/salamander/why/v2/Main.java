package me.salamander.why.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.salamander.why.debug.LocalVariableMapper;
import me.salamander.why.debug.MethodModifier;
import me.salamander.why.debug.patterns.BytecodePattern;
import me.salamander.why.debug.patterns.PackedInequalityPattern;
import me.salamander.why.v2.patterns2.BlockPosOffsetPattern;
import me.salamander.why.v2.patterns2.CheckInvalidPosPattern;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.light.ChunkBlockLightProvider;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import org.apache.commons.lang3.NotImplementedException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.util.Textifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private static Map<String, MethodInfo> methodInfo = new HashMap<>();
    private static String[] unpackingMethods = new String[3];

    public static void main(String[] args) {
        ClassNode testClass = createClassNode(ChunkBlockLightProvider.class);

        BasicSourceInterpreter interpreter = new BasicSourceInterpreter(Opcodes.ASM9);
        Analyzer<BasicSourceValue> analyzer = new Analyzer<>(interpreter);
        List<MethodNode> newMethods = new ArrayList<>();
        for(MethodNode methodNode : testClass.methods){
            System.out.println("Analyzing " + methodNode.name + " " + methodNode.desc);
            try {
                interpreter.clearCache();
                analyzer.analyze(testClass.name, methodNode);
                Frame<BasicSourceValue>[] frames = analyzer.getFrames();
                AbstractInsnNode[] instructions = methodNode.instructions.toArray();
                Map<AbstractInsnNode, Integer> instructionIndexMap = new HashMap<>(instructions.length);

                //Create a map that can get the index of any instruction
                for(int i = 0; i < instructions.length; i++){
                    instructionIndexMap.put(instructions[i], i);
                }

                //Consumer info allows for easy lookup of what a value gets consumed by
                Map<BasicSourceValue, Set<Integer>> consumers = mapConsumerCache(interpreter.getConsumers(), instructionIndexMap);

                //Figure out what variables should turned into 3ints
                Set<Integer> expandedVariables = getExpandedVariables(frames, instructions, consumers);

                //Apply those changes
                MethodNode newMethod = modifyMethod(methodNode, frames, consumers, expandedVariables, instructionIndexMap);

                if(newMethod != null){
                    newMethods.add(newMethod);
                }

                if(methodNode.localVariables == null){
                    System.out.println("\tExpanded Variables: " + expandedVariables);
                }else{
                    StringBuilder varNames = new StringBuilder();
                    Set<Integer> remaining = new HashSet<>(expandedVariables);
                    for(LocalVariableNode localVar : methodNode.localVariables){
                        if(remaining.contains(localVar.index)){
                            varNames.append(localVar.name).append(" ");
                            remaining.remove(localVar.index);
                        }
                    }

                    for(int index: remaining){
                        varNames.append("var").append(index).append(" ");
                    }

                    System.out.println("\tExpanded Variables: { " + varNames + "}");
                }
            }catch (AnalyzerException e){
                e.printStackTrace();
            }
        }

        testClass.methods.addAll(newMethods);

        MethodModifier.saveClass(testClass, "");
    }

    /**
     * Changes the methods code in order to make it use triple ints where necessary. Will report any changes that could not/should be done
     * @param frames The frames of the method created by a {@code BasicSourceInterpreter} and {@code Analyzer<BasicSourceNode>}
     * @param consumers The consumer information generated by the interpreter
     * @param expandedVariables The variables indices that should be expanded
     * @param instructionIndexMap
     * @return A modified copy of the method or null if no changes were done
     */
    private static MethodNode modifyMethod(MethodNode methodNode, Frame<BasicSourceValue>[] frames, Map<BasicSourceValue, Set<Integer>> consumers, Set<Integer> expandedVariables, Map<AbstractInsnNode, Integer> instructionIndexMap) {
        //Copy the whole method
        MethodNode newMethod = MethodModifier.copy(methodNode);
        boolean changedAnything = false;

        //Create the variable mapper
        LocalVariableMapper variableMapper = new LocalVariableMapper();
        for(int var : expandedVariables){
            variableMapper.addTransformedParameter(var);
            changedAnything = true;
        }
        variableMapper.generate();

        //Generate the descriptor for the modified method
        modifyDescriptor(newMethod, expandedVariables);

        //If the descriptor didn't get modified add -3int to the end of its name to prevent clashes
        if(newMethod.desc.equals(methodNode.desc) && !newMethod.name.startsWith("<")){
            newMethod.name += "3int";
        }

        AbstractInsnNode[] instructions = newMethod.instructions.toArray(); //Generate instructions array

        //Step one: remap all variables instructions + check that all expanded variables correspond to longs
        for(int i = 0; i < instructions.length; i++){
            AbstractInsnNode instruction = newMethod.instructions.get(i);
            if(instruction instanceof VarInsnNode varNode){
                if(variableMapper.isATransformedLong(varNode.var)){
                    if(instruction.getOpcode() != Opcodes.LLOAD && instruction.getOpcode() != Opcodes.LSTORE){
                        throw new IllegalStateException("Accessing mapped local variable but not as a long!");
                    }
                }
                varNode.var = variableMapper.mapLocalVariable(varNode.var);
                changedAnything = true;
            }else if(instruction instanceof IincInsnNode iincNode){
                if(variableMapper.isATransformedLong(iincNode.var)){
                    throw new IllegalStateException("Incrementing mapped variable :(");
                }
                iincNode.var = variableMapper.mapLocalVariable(iincNode.var);
                changedAnything = true;
            }
        }

        //Then change all accesses and uses of packed variables
        for(int i = 0; i < instructions.length; i++){
            AbstractInsnNode instruction = instructions[i];

            if(instruction instanceof MethodInsnNode methodCall){
                String methodID = methodCall.owner + "#" + methodCall.name + " " + methodCall.desc;
                boolean wasUnpacked = false;
                for(int axis = 0; axis < 3; axis++){
                    //Find if this is an unpacking method and if so modify it's emitter
                    if(unpackingMethods[axis].equals(methodID)){
                        wasUnpacked = true;
                        newMethod.instructions.remove(methodCall);

                        Set<AbstractInsnNode> emitters = topOfFrame(frames[i]).getSource();
                        for(AbstractInsnNode otherEmitter: emitters){ //otherEmitter is an instruction from the original method and should NOT be modified
                            int emitterIndex = instructionIndexMap.get(otherEmitter);
                            AbstractInsnNode emitter = instructions[emitterIndex];
                            modifyPosEmitter(frames, instructions, emitter, emitterIndex, newMethod.instructions, variableMapper, axis, instructionIndexMap, consumers);
                        }
                        break;
                    }
                }

                //Only runs if the previous section changed nothing
                if(!wasUnpacked){
                    MethodInfo methodInfo;
                    String newName = methodCall.name;
                    String newOwner = methodCall.owner;
                    String descriptorVerifier = null;
                    if((methodInfo = Main.methodInfo.get(methodID)) != null){
                        if(methodInfo.returnsPackedBlockPos()){
                            continue;
                        }

                        newName = methodInfo.getNewName();
                        newOwner = methodInfo.getNewOwner();
                        descriptorVerifier = methodInfo.getNewDesc();
                    }

                    //Figure out the amount of arguments this method call takes and their locations on the stack
                    boolean isStatic = methodCall.getOpcode() == Opcodes.INVOKESTATIC;

                    int numArgs = MethodInfo.getNumArgs(methodCall.desc);
                    if(!isStatic){
                        numArgs++;
                    }

                    int firstArgIndex = frames[i].getStackSize() - numArgs;

                    //This will record what parameters get turned into 3 ints to change the method signature correctly
                    List<Integer> expandedIndices = new ArrayList<>();


                    for(int offset = 0; offset < numArgs; offset++){
                        //Get argument value from current frame
                        BasicSourceValue argument = frames[i].getStack(firstArgIndex + offset);
                        for(AbstractInsnNode otherEmitter: argument.getSource()){
                            int emitterIndex = instructionIndexMap.get(otherEmitter);
                            //Get the emitter
                            AbstractInsnNode emitter = instructions[emitterIndex];
                            //Check if the emitter should be turned into a 3int emitter and if so track that and modify the emitter
                            if(modifyPosEmitter(frames, instructions, emitter, emitterIndex, newMethod.instructions, variableMapper, -1, instructionIndexMap, consumers)){
                                expandedIndices.add(offset);
                            }
                        }
                    }

                    //Modify the descriptor
                    String newDescriptor = modifyDescriptor(methodCall.desc, expandedIndices, isStatic, false);
                    assert descriptorVerifier == null || descriptorVerifier.equals(newDescriptor);

                    //Log transformation and change method call info
                    if(!newDescriptor.equals(methodCall.desc)) {
                        System.out.println("Info: Transforming " + methodID + " into " + newOwner + "#" + newName + " " + newDescriptor);
                        methodCall.owner = newOwner;
                        methodCall.name = newName;
                        methodCall.desc = newDescriptor;
                    }
                }
            }
        }

        //Apply extra patterns for some more precise changes
        List<BytecodePattern> patterns = new ArrayList<>();
        patterns.add(new BlockPosOffsetPattern());
        patterns.add(new CheckInvalidPosPattern());
        patterns.add(new PackedInequalityPattern());

        MethodModifier.applyPatterns(newMethod.instructions, variableMapper, patterns);

        //Create local variable name table
        List<LocalVariableNode> localVariables = new ArrayList<>();
        for(LocalVariableNode var : newMethod.localVariables){
            int mapped = variableMapper.mapLocalVariable(var.index);
            boolean isExpanded = variableMapper.isATransformedLong(var.index);
            if(isExpanded){
                localVariables.add(new LocalVariableNode(var.name + "_x", "I", null, var.start, var.end, mapped));
                localVariables.add(new LocalVariableNode(var.name + "_y", "I", null, var.start, var.end, mapped + 1));
                localVariables.add(new LocalVariableNode(var.name + "_z", "I", null, var.start, var.end, mapped + 2));
            }else{
                localVariables.add(new LocalVariableNode(var.name, var.desc, var.signature, var.start, var.end, mapped));
            }
        }

        System.out.println(localVariables.size());

        newMethod.localVariables = localVariables;
        newMethod.parameters = null;

        return changedAnything ? newMethod : null;
    }

    /**
     * Modifies an instruction or series of instructions that emit a packed position so as to only emit a single int representing one of the 3 coordinates or emit all 3
     * @param frames Array of frames generated by an Evaluator
     * @param instructions Array of instructions
     * @param emitter Instruction that emitted that packed block position
     * @param integer Index into {@code instructions} of emitter
     * @param insnList The {@code InsnList} of the method
     * @param offset Should be 0 for x-coordinate, 1 for y, 2 for z and -1 for all 3
     * @return Whether or not any modifications occurred i.e If the instruction actually emits a packed pos
     */
    private static boolean modifyPosEmitter(Frame<BasicSourceValue>[] frames, AbstractInsnNode[] instructions, AbstractInsnNode emitter, Integer integer, InsnList insnList, LocalVariableMapper variableMapper, int offset, Map<AbstractInsnNode, Integer> instructionIndexMap, Map<BasicSourceValue, Set<Integer>> consumers) {
        if(emitter.getOpcode() == Opcodes.LLOAD){
            VarInsnNode loader = (VarInsnNode) emitter;
            if(!variableMapper.isARemappedTransformedLong(loader.var)){
                return false; //Only change anything if the loaded long is a tranformed one
            }
            if(offset != -1){ //If you only want a single axis just modify the instruction
                loader.setOpcode(Opcodes.ILOAD);
                loader.var += offset;
            }else{
                //Otherwise insert two more instructions so as to load all 3 ints
                loader.setOpcode(Opcodes.ILOAD);
                insnList.insertBefore(loader, new VarInsnNode(Opcodes.ILOAD, loader.var));
                insnList.insertBefore(loader, new VarInsnNode(Opcodes.ILOAD, loader.var + 1));
                loader.var += 2;
            }
            return true;
        }else if(emitter instanceof VarInsnNode) {
            return false; //Any other VarInsnNode is just a normal local variable
        }else if(emitter instanceof LdcInsnNode constantLoad){
            System.out.println("Expanding Constant");
            //Expand Long.MAX_VALUE to 3 Integer.MAX_VALUE
            if(constantLoad.cst instanceof Long && (Long) constantLoad.cst == Long.MAX_VALUE){
                int amount = offset == -1 ? 3 : 1;
                for(int i = 0; i < amount; i++){
                    insnList.insert(emitter, new LdcInsnNode(Integer.MAX_VALUE));
                }
                insnList.remove(emitter);
                return true;
            }
        }else if(emitter instanceof FieldInsnNode){
            return false;
        }else if(emitter instanceof TypeInsnNode) {
            return false;
        }else if(emitter instanceof MethodInsnNode methodCall){
            String methodID = methodCall.owner + "#" + methodCall.name + " " + methodCall.desc;
            MethodInfo methodInfo = Main.methodInfo.get(methodID);
            if(methodInfo != null){
                //Change BlockPos.asLong() calls to three separate calls to getX, getY and getZ
                if(methodInfo.returnsPackedBlockPos()){
                    if(methodCall.name.equals("asLong")){
                        methodCall.desc = "()I";
                        if(offset == 0){
                            methodCall.name = "getX";
                        }else if(offset == 1){
                            methodCall.name = "getY";
                        }else if(offset == 2){
                            methodCall.name = "getZ";
                        }else{
                            insnList.insertBefore(emitter, new InsnNode(Opcodes.DUP));
                            insnList.insertBefore(emitter, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/util/math/BlockPos", "getX", "()I"));
                            insnList.insertBefore(emitter, new InsnNode(Opcodes.SWAP));
                            insnList.insertBefore(emitter, new InsnNode(Opcodes.DUP));
                            insnList.insertBefore(emitter, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/util/math/BlockPos", "getY", "()I"));
                            insnList.insertBefore(emitter, new InsnNode(Opcodes.SWAP));
                            methodCall.name = "getZ";
                        }
                    }
                    return true;
                }
            }
            return false;
        }else if(emitter instanceof InsnNode){

        }else{
            System.out.println("Warning: Don't know what to do with " + insnToString(emitter));
            return false;
        }

        return false;
    }

    public static void modifyDescriptor(MethodNode methodNode, Set<Integer> expandedVariables) {
        Type returnType = Type.getReturnType(methodNode.desc);
        Type[] args = Type.getArgumentTypes(methodNode.desc);

        List<Type> newArgumentTypes = new ArrayList<>();
        int i = 0;
        if((methodNode.access & Opcodes.ACC_STATIC) == 0) i++;
        for(Type argument: args){
            if(expandedVariables.contains(i)){
                for(int j = 0; j < 3; j++) newArgumentTypes.add(Type.INT_TYPE);
            }else{
                newArgumentTypes.add(argument);
            }

            i += argument.getSize();
        }

        methodNode.desc = modifyDescriptor(methodNode.desc, expandedVariables, (methodNode.access & Opcodes.ACC_STATIC) != 0, true);
    }

    public static String modifyDescriptor(String descriptor, Collection<Integer> expandedVariables, boolean isStatic, boolean adjustForVarWidth){
        Type returnType = Type.getReturnType(descriptor);
        Type[] args = Type.getArgumentTypes(descriptor);

        List<Type> newArgumentTypes = new ArrayList<>();
        int i = 0;
        if(!isStatic) i++;
        for(Type argument: args){
            if(expandedVariables.contains(i)){
                for(int j = 0; j < 3; j++) newArgumentTypes.add(Type.INT_TYPE);
            }else{
                newArgumentTypes.add(argument);
            }

            i += adjustForVarWidth ? argument.getSize() : 1;
        }

        return Type.getMethodDescriptor(returnType, newArgumentTypes.toArray(Type[]::new));
    }

    private static Map<BasicSourceValue, Set<Integer>> mapConsumerCache(Map<BasicSourceValue, Set<AbstractInsnNode>> consumers, Map<AbstractInsnNode, Integer> instructionIndexMap) {
        Map<BasicSourceValue, Set<Integer>> mapped = new HashMap<>();

        for(Map.Entry<BasicSourceValue, Set<AbstractInsnNode>> entry: consumers.entrySet()){
            mapped.put(entry.getKey(), entry.getValue().stream().map(instructionIndexMap::get).collect(Collectors.toSet()));
        }

        return mapped;
    }

    private static Set<Integer> getExpandedVariables(Frame<BasicSourceValue>[] frames, AbstractInsnNode[] instructions, Map<BasicSourceValue, Set<Integer>> consumerInfo) {
        Set<Integer> expandedVariables = new HashSet<>();
        Set<Integer> placesWherePackedBlockPosAreProduced = new HashSet<>();

        //Inspect ALL method calls. This only has to be done once
        for(int i = 0; i < frames.length; i++){
            AbstractInsnNode instruction = instructions[i];
            if(instruction instanceof MethodInsnNode methodCall){
                String methodName = methodCall.owner + "#" + methodCall.name + " " + methodCall.desc;
                MethodInfo methodInfo = Main.methodInfo.get(methodName);
                if(methodInfo == null) continue;

                Frame<BasicSourceValue> currentFrame = frames[i];
                int firstArgIndex = currentFrame.getStackSize() - methodInfo.getNumArgs();
                if(methodInfo.hasPackedArguments()){
                    for(int packedArgument : methodInfo.getExpandedIndices()){
                        BasicSourceValue valueOnStack = currentFrame.getStack(firstArgIndex + packedArgument);
                        if(valueOnStack != null){
                            expandedVariables.addAll(valueOnStack.getLocalVars());
                        }
                    }
                }

                if(methodInfo.returnsPackedBlockPos()){
                    Frame<BasicSourceValue> nextFrame = frames[i + 1];
                    BasicSourceValue top = topOfFrame(nextFrame);
                    Set<Integer> consumerIndices = consumerInfo.get(top);
                    placesWherePackedBlockPosAreProduced.add(i);
                    for(int consumerIndex : consumerIndices){
                        AbstractInsnNode consumer = instructions[consumerIndex];
                        if(consumer instanceof VarInsnNode storeInstruction){
                            expandedVariables.add(storeInstruction.var);
                        }else{
                            //System.out.println("Unhandled Consumer Instruction: " + insnToString(instruction));
                        }
                    }
                }
            }
        }

        //Until no more packed local variables are found look at their usages to find more
        boolean changed = true;
        while(changed){
            changed = false;
            for(int i = 0; i < frames.length; i++){
                if(instructions[i].getOpcode() == Opcodes.LLOAD){
                    VarInsnNode loadInsn = (VarInsnNode) instructions[i];
                    if(expandedVariables.contains(loadInsn.var)){
                        if(placesWherePackedBlockPosAreProduced.add(i)){
                            Set<Integer> consumers = consumerInfo.get(topOfFrame(frames[i + 1]));
                            BasicSourceValue loadedLong = frames[i+1].getStack(frames[i+1].getStackSize() - 1);

                            for(int consumerIndex: consumers){
                                AbstractInsnNode consumer = instructions[consumerIndex];
                                Frame<BasicSourceValue> frame = frames[consumerIndex];
                                if(consumer.getOpcode() == Opcodes.LCMP){
                                    BasicSourceValue operandOne = frame.getStack(frame.getStackSize() - 1);
                                    BasicSourceValue operandTwo = frame.getStack(frame.getStackSize() - 2);

                                    if(operandOne != loadedLong){
                                        if(expandedVariables.addAll(operandOne.getLocalVars())){
                                            changed = true;
                                        }
                                    }else{
                                        if(expandedVariables.addAll(operandTwo.getLocalVars())){
                                            changed = true;
                                        }
                                    }
                                }else{
                                    //System.out.println("Unhandled Consumer Instruction: " + insnToString(consumer));
                                }
                            }
                        }
                    }
                }
            }
        }

        return expandedVariables;
    }

    private static String insnToString(AbstractInsnNode instruction){
        if(instruction instanceof MethodInsnNode methodCall){
            String callType = switch (instruction.getOpcode()){
                case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
                case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
                case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
                default -> throw new IllegalStateException("Unexpected value: " + instruction.getOpcode());
            };

            return callType + " " + methodCall.owner + "#" + methodCall.name + " " + methodCall.desc;
        }

        return instruction.toString() + " " + instruction.getOpcode();
    }

    private static <T extends Value> T topOfFrame(Frame<T> frame) {
        return frame.getStack(frame.getStackSize() - 1);
    }



    public static ClassNode createClassNode(Class<?> clazz){
        ClassNode node = new ClassNode();
        try
        {
            String fileName = clazz.getName().replace('.', '/') + ".class";
            ClassReader reader = new ClassReader(ClassLoader.getSystemResourceAsStream(fileName));
            reader.accept(node, 0);

            return node;
        } catch (IOException e)
        {
            e.printStackTrace();
        }

        throw new RuntimeException("Couldn't create ClassNode for class " + clazz.getName());
    }

    static {
        JsonParser parser = new JsonParser();
        JsonObject root;
        try {
            InputStream is = Main.class.getResourceAsStream("/config.json");
            root = parser.parse(new String(is.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't load config", e);
        }

        JsonObject methodInfo = root.getAsJsonObject().get("method_info").getAsJsonObject();
        for(Map.Entry<String, JsonElement> entry: methodInfo.entrySet()){
            String[] parts = entry.getKey().split(" ");
            String[] moreParts = parts[0].split("#");
            Main.methodInfo.put(entry.getKey(), new MethodInfo(entry.getValue().getAsJsonObject(), moreParts[0], moreParts[1], parts[1]));
        }

        JsonArray unpackers = root.get("unpacking").getAsJsonArray();
        for(int i = 0; i < 3; i++) unpackingMethods[i] = unpackers.get(i).getAsString();
    }
}
