package me.salamander.why.v2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class MethodInfo {
    private final boolean returnsExpandedLong;
    private final Set<Integer> expandedArgumentIndices;
    private final int numArgs;

    private final String newOwner;
    private final String newName;
    private final String newDesc;

    public MethodInfo(JsonObject root, String methodOwner, String methodName, String methodDescriptor){
        System.out.println(methodDescriptor);
        returnsExpandedLong = root.get("returns_pos").getAsBoolean();

        boolean isStatic = false;
        JsonElement staticElement = root.get("static");
        if(staticElement != null) isStatic = staticElement.getAsBoolean();
        int offset = isStatic ? 0 : 1;

        expandedArgumentIndices = new HashSet<>();
        root.get("blockpos_args").getAsJsonArray().forEach((e) -> {
            expandedArgumentIndices.add(e.getAsInt() + offset);
        });

        numArgs = getNumArgs(methodDescriptor) + offset;

        newDesc = Main.modifyDescriptor(methodDescriptor, expandedArgumentIndices, isStatic, false);

        String newName = methodName;
        String newOwner = methodOwner;
        JsonElement newNameElement = root.get("rename");
        if(newNameElement != null){
            String newNameData = newNameElement.getAsString();
            String[] ownerAndName = newNameData.split("#");
            if(ownerAndName.length == 1){
                newName = ownerAndName[0];
            }else{
                newName = ownerAndName[1];
                newOwner = ownerAndName[0];
            }
        }

        this.newName = newName;
        this.newOwner = newOwner;
    }

    //ASM doesn't specify a method that does exactly this. However this code is mostly taken from Type.getArgumentAndReturnSizes
    public static int getNumArgs(String methodDescriptor) {
        int numArgs = 0;

        int currentIndex = 1;
        char currentChar = methodDescriptor.charAt(currentIndex);

        while (currentChar != ')'){
            while (methodDescriptor.charAt(currentIndex) == '['){
                currentIndex++;
            }
            if(methodDescriptor.charAt(currentIndex) == 'L'){
                int semicolonOffset = methodDescriptor.indexOf(';', currentIndex);
                currentIndex = Math.max(semicolonOffset, currentIndex);
            }
            currentIndex++;
            numArgs++;
            currentChar = methodDescriptor.charAt(currentIndex);
        }

        return numArgs;
    }

    public boolean returnsPackedBlockPos(){
        return returnsExpandedLong;
    }

    public Set<Integer> getExpandedIndices(){
        return expandedArgumentIndices;
    }

    public boolean hasPackedArguments(){
        return expandedArgumentIndices.size() != 0;
    }

    public int getNumArgs() {
        return numArgs;
    }

    public String getNewOwner() {
        return newOwner;
    }

    public String getNewName() {
        return newName;
    }

    public String getNewDesc() {
        return newDesc;
    }
}
