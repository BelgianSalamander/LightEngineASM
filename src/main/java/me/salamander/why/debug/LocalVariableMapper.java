package me.salamander.why.debug;

import java.util.*;

public class LocalVariableMapper {
    private final List<Integer> transformedParameters = new ArrayList<>();
    //private final Map<Integer, Integer> parameterMapper = new HashMap<>();

    public void addTransformedParameter(int index){
        transformedParameters.add(index);
    }

    public int mapLocalVariable(int index){
        int mappedIndex = index;
        for(int transformed : transformedParameters){
            if(index > transformed) mappedIndex++;
        }

        return mappedIndex;
    }

    public boolean isATransformedLong(int index){
        return transformedParameters.contains(index);
    }

    public boolean isARemappedTransformedLong(int index){
        for(int unmappedIndex : transformedParameters){
            if(mapLocalVariable(unmappedIndex) == index) return true;
        }
        return false;
    }

    public int getLocalVariableOffset() {
        return transformedParameters.size();
    }
}
