package me.salamander.why.v2;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.analysis.Value;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class BasicSourceValue implements Value {
    private static int IDCounter = 0;

    private final Type type;
    private final Set<AbstractInsnNode> source;
    private final Set<Integer> localVars;

    public BasicSourceValue(Type type){
        this.type = type;
        this.source = new HashSet<>();
        this.localVars = new HashSet<>();
    }

    public BasicSourceValue(Type type, int localVar){
        this.type = type;
        this.source = new HashSet<>();
        this.localVars = new HashSet<>();
        localVars.add(localVar);
    }

    public BasicSourceValue(Type type, AbstractInsnNode source){
        this(type);
        this.source.add(source);
    }

    public BasicSourceValue(Type type, AbstractInsnNode source, int localVar){
        this.type = type;
        this.source = new HashSet<>();
        this.localVars = new HashSet<>();

        this.source.add(source);
        this.localVars.add(localVar);
    }

    public BasicSourceValue(Type type, AbstractInsnNode source, Set<Integer> localVars){
        this.type = type;
        this.source = new HashSet<>();
        this.localVars = localVars;

        this.source.add(source);
    }

    public BasicSourceValue(Type type, Set<AbstractInsnNode> source, Set<Integer> localVars) {
        this.type = type;
        this.source = source;
        this.localVars = localVars;
    }

    @Override
    public int getSize() {
        return type == Type.LONG_TYPE || type == Type.DOUBLE_TYPE ? 2 : 1;
    }

    public Type getType() {
        return type;
    }

    public Set<AbstractInsnNode> getSource() {
        return source;
    }

    public Set<Integer> getLocalVars() {
        return localVars;
    }

    public BasicSourceValue merge(BasicSourceValue other){
        /*if(!Objects.equals(this.type, other.type)){ //TODO: Somehow manage this
            System.out.println("Combining two types");
        }*/
        return new BasicSourceValue(this.type, union(this.source, other.source), union(this.localVars, other.localVars));
    }

    public static <T> Set<T> union(Set<T> first, Set<T> second){
        Set<T> union = new HashSet<>(first);
        union.addAll(second);
        return second;
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof BasicSourceValue)){
            return false;
        }
        BasicSourceValue sourceValue = (BasicSourceValue) obj;
        return Objects.equals(this.type, sourceValue.type) && this.source.equals(sourceValue.source) && this.localVars.equals(sourceValue.localVars);
    }
}
