package me.salamander.why.debug;

import com.google.gson.*;
import com.mojang.datafixers.util.Pair;
import me.salamander.why.debug.patterns.*;
import net.minecraft.world.chunk.light.ChunkBlockLightProvider;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MethodModifier {
    private static Printer printer = new Textifier();
    private static TraceMethodVisitor mp = new TraceMethodVisitor(printer);

    public static void main(String[] args) {
        JsonParser parser = new JsonParser();
        createClassNode(ChunkBlockLightProvider.class);

        try {
            String data = new String(MethodModifier.class.getResourceAsStream("/remaps.json").readAllBytes(), StandardCharsets.UTF_8);
            JsonElement root = parser.parse(data);
            JsonArray classesToTransform = root.getAsJsonArray();
            for(JsonElement classInfoElement : classesToTransform){
                JsonObject classInfo = classInfoElement.getAsJsonObject();
                String className = classInfo.get("class").getAsString();
                ClassNode classNode = createClassNode(className);
                for(JsonElement methodInfoElement : classInfo.get("methods").getAsJsonArray()){
                    JsonObject methodInfo = methodInfoElement.getAsJsonObject();
                    JsonElement disable = methodInfo.get("disable");
                    if(disable != null) if(disable.getAsBoolean()) continue;
                    String name = methodInfo.get("name").getAsString();
                    String descriptor = methodInfo.get("descriptor").getAsString();

                    List<Integer> expandedVariables = new ArrayList<>();
                    methodInfo.get("expanded_variables").getAsJsonArray().forEach((e) -> expandedVariables.add(e.getAsInt()));

                    Map<String, String> transformedMethods = new HashMap<>();
                    methodInfo.get("transformed_methods").getAsJsonObject().entrySet().forEach((entry) -> transformedMethods.put(entry.getKey(), entry.getValue().getAsString()));

                    List<String> patternNames = new ArrayList<>();
                    methodInfo.get("patterns").getAsJsonArray().forEach((e) -> patternNames.add(e.getAsString()));

                    List<BytecodePattern> patterns = new ArrayList<>();
                    patternNames.forEach(patternName -> {
                        BytecodePattern pattern = Patterns.getPattern(patternName, transformedMethods);
                        if(pattern != null){
                            patterns.add(pattern);
                            return;
                        }

                        System.out.println("Warning: Unknown Pattern: " + patternName);
                    });

                    boolean copy = true;
                    JsonElement copyElement = methodInfo.get("copy");
                    if(copyElement != null){
                        copy = copyElement.getAsBoolean();
                    }

                    MethodNode newMethod = null;
                    for(MethodNode methodNode : classNode.methods){
                        if(methodNode.name.equals(name) && methodNode.desc.equals(descriptor)){
                            newMethod = modifyMethod(classNode, methodNode, patterns, expandedVariables, copy);
                        }
                    }

                    if(newMethod != null && copy){
                        classNode.methods.add(newMethod);
                    }else if(copy){
                        System.out.println("Warning: Couldn't find method " + name + " " + descriptor);
                    }
                }
                byte[] bytes = saveClass(classNode, "");
                ClassReader classReader = new ClassReader(bytes);
                ClassNode node = new ClassNode();
                classReader.accept(node, 0);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static byte[] saveClass(ClassNode classNode, String suffix){
        ClassWriter classWriter = new ClassWriter(0);

        classNode.accept(classWriter);
        Path savePath = Path.of(classNode.name + suffix + ".class");

        try {
            if(!savePath.toFile().exists()){
                savePath.toFile().getParentFile().mkdirs();
                Files.createFile(savePath);
            }

            FileOutputStream fout = new FileOutputStream(savePath.toAbsolutePath().toString());
            byte[] bytes;
            fout.write(bytes = classWriter.toByteArray());
            fout.close();
            System.out.println("Saved class at " + savePath.toAbsolutePath());
            return bytes;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static MethodNode modifyMethod(ClassNode classNode, MethodNode methodNode, List<BytecodePattern> patterns, List<Integer> expandedVariables, boolean copy){
        System.out.println("Modifying " + methodNode.name);
        MethodNode newMethod = copy ? copy(methodNode) : methodNode;

        var currentDescriptor = ParameterInfo.parseDescriptor(methodNode.desc);
        System.out.println("New Descriptor: " + (newMethod.desc = modifyDescriptor(methodNode, currentDescriptor, expandedVariables)));
        
        LocalVariableMapper mapper = new LocalVariableMapper();
        for(int expandedVariable : expandedVariables){
            mapper.addTransformedParameter(expandedVariable);
        }

        InsnList code = modifyCodeV3(newMethod.instructions, mapper, patterns);
        newMethod.instructions = code;

        //Remap local var names
        List<LocalVariableNode> localVariables = new ArrayList<>();
        for(LocalVariableNode var : newMethod.localVariables){
            int mapped = mapper.mapLocalVariable(var.index);
            boolean isExpanded = mapper.isATransformedLong(var.index);
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

        return newMethod;
    }

    public static MethodNode copy(MethodNode method){
        ClassNode classNode = new ClassNode();
        //MethodNode other = new MethodNode();
        method.accept(classNode);
        return classNode.methods.get(0);
    }

    private static InsnList copy(InsnList insnList) {
        MethodNode mv = new MethodNode();
        insnList.accept(mv);
        return mv.instructions;
    }

    private static InsnList modifyCodeV3(InsnList insnList, LocalVariableMapper varMapper, List<BytecodePattern> patterns){
        for(AbstractInsnNode instruction : insnList){
            if(instruction instanceof FrameNode){
                insnList.remove(instruction);
            }
        }

        remapLocalVariables(insnList, varMapper);
        applyPatterns(insnList, varMapper, patterns);

        return insnList;
    }

    public static void applyPatterns(InsnList instructions, LocalVariableMapper mapper, List<BytecodePattern> patterns) {
        int currentIndex = 0;

        while (currentIndex < instructions.size()){
            for(BytecodePattern pattern: patterns){
                if(pattern.apply(instructions, mapper, currentIndex)){
                    break;
                }
            }
            currentIndex++;
        }
    }

    private static void remapLocalVariables(InsnList instructions, LocalVariableMapper mappedVariables){
        for(AbstractInsnNode instruction: instructions){
            if(instruction instanceof VarInsnNode localVarInstruction){
                localVarInstruction.var = mappedVariables.mapLocalVariable(localVarInstruction.var);
            }else if(instruction instanceof IincInsnNode incrementInstruction){
                incrementInstruction.var = mappedVariables.mapLocalVariable((incrementInstruction).var);
            }
        }
    }

    public static void mapInstruction(VarInsnNode insnNode, LocalVariableMapper mapper){
        insnNode.var = mapper.mapLocalVariable(insnNode.var);
    }

    public static String modifyDescriptor(MethodNode method, Pair<List<ParameterInfo>, ParameterInfo> descriptor, List<Integer> expandedVariables){
        List<ParameterInfo> newParameters = new ArrayList<>();

        int originalLocalVariableOffset = 0;

        if((method.access & Opcodes.ACC_STATIC) == 0){
            originalLocalVariableOffset++;
        }

        for(ParameterInfo originalParameter : descriptor.getFirst()){
            if (originalParameter == ParameterInfo.LONG && expandedVariables.contains(originalLocalVariableOffset)) {
                originalLocalVariableOffset += 2;
                for(int i = 0; i < 3; i++)
                    newParameters.add(ParameterInfo.INT);
            }else{
                originalLocalVariableOffset += originalParameter.numSlots();
                newParameters.add(originalParameter);
            }
        }

        System.out.println("Modified Parameters:");
        for(ParameterInfo parameter: newParameters){
            System.out.println("\t" + parameter);
        }

        return ParameterInfo.writeDescriptor(newParameters, descriptor.getSecond());
    }

    public static String modifyDescriptor(MethodNode method, List<Integer> expandedVariables){
        var currentDescriptor = ParameterInfo.parseDescriptor(method.desc);

        return modifyDescriptor(method, currentDescriptor, expandedVariables);
    }

    public static String insnToString(AbstractInsnNode instruction){
        instruction.accept(mp);
        StringWriter sw = new StringWriter();
        printer.print(new PrintWriter(sw));
        printer.getText().clear();
        return sw.toString();
    }

    public static ClassNode createClassNode(String path){
        ClassNode node = new ClassNode();
        try
        {
            System.out.println("Loading: " + path);
            ClassReader reader = new ClassReader(ClassLoader.getSystemResourceAsStream(path));
            reader.accept(node, 0);

            return node;
        } catch (IOException e)
        {
            e.printStackTrace();
        }

        throw new RuntimeException("Couldn't create ClassNode for class " + path);
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
}
