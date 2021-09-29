import me.salamander.why.v2.MethodInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MethodInfoTests {
    @Test
    public void testNumArgs(){
        assertEquals(MethodInfo.getNumArgs("(III)V"), 3);
        assertEquals(MethodInfo.getNumArgs("([[[Lnet/minecraft/core/SectionPos;JI)Z"), 3);
        assertEquals(MethodInfo.getNumArgs("(JJII)I"), 4);
    }
}
