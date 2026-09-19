package chem.molgraph;

import chem.molgraph.engine.StereoMath;
import chem.molgraph.domain.Stereo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StereoMathTest {

    @Test
    void parityOfPermutation() {
        assertEquals(0, StereoMath.permutationParity(List.of("a", "b", "c"), List.of("a", "b", "c")));
        assertEquals(1, StereoMath.permutationParity(List.of("a", "b", "c"), List.of("b", "a", "c")));
        assertEquals(0, StereoMath.permutationParity(List.of("a", "b", "c", "d"), List.of("d", "c", "b", "a")));
    }

    @Test
    void descriptorFlipsWithOddPermutation() {
        List<String> stored = List.of("w", "x", "y", "z");
        List<String> swapped = List.of("x", "w", "y", "z");
        assertEquals(1, StereoMath.descriptor(Stereo.UP, stored, stored));
        assertEquals(-1, StereoMath.descriptor(Stereo.UP, stored, swapped));
        assertEquals(-1, StereoMath.descriptor(Stereo.DOWN, stored, stored));
        assertEquals(1, StereoMath.descriptor(Stereo.DOWN, stored, swapped));
        assertEquals(0, StereoMath.descriptor(Stereo.NONE, stored, swapped));
    }
}
