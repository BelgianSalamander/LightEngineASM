package me.salamander.why.debug;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.light.BlockLightStorage;
import net.minecraft.world.chunk.light.ChunkBlockLightProvider;

import java.util.concurrent.Callable;

public class TestClass {
    public int doStuff(int a, long l){
        int i = BlockPos.unpackLongX(l);
        int j = BlockPos.unpackLongY(l);
        int k = BlockPos.unpackLongZ(l);
        return doubleStuff(l, a) + i - j * k;
    }

    public int doubleStuff(long l, int b){
        int x = BlockPos.unpackLongX(l);
        int y = BlockPos.unpackLongY(l);
        int z = BlockPos.unpackLongZ(l);

        return (x + y + z) * 2 + b;
    }

    public int test(int i, Direction direction){
        return i + direction.getOffsetX();
    }
    
    private Direction[] DIRECTIONS = new Direction[1];
    BlockLightStorage lightStorage = null;

    protected void propagateLevel(int id_x, int id_y, int id_z, int level, boolean decrease) {
        long l = ChunkSectionPos.fromBlockPos(id_x);
        Direction[] var8 = DIRECTIONS;
        int var9 = var8.length;

        for(int var10 = 0; var10 < var9; ++var10) {
            Direction direction = var8[var10];
            int m_x = id_x + direction.getOffsetX();
            int m_y = id_y + direction.getOffsetY();
            int m_z = id_z + direction.getOffsetZ();
            long n = ChunkSectionPos.fromBlockPos(m_x);
        }

    }

    private void checkPos(int x1, int y1, int z1){
        if(x1 != Integer.MIN_VALUE && y1 != Integer.MIN_VALUE && z1 != Integer.MIN_VALUE){
            System.out.println("Invalid Position");
        }
    }

    private int getLength(String string){
        int length = 0;
        for(int j = 0; j < string.length(); j++){
            length++;
        }
        return length;
    }
}
