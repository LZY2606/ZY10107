package chem.molgraph.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Atom(
        String id,
        String element,
        int isotope,
        int charge,
        Stereo stereo,
        List<String> stereoOrder,
        String originTag,
        Double x,
        Double y) {

    public Atom withCoordinates(Double nx, Double ny) {
        return new Atom(id, element, isotope, charge, stereo, stereoOrder, originTag, nx, ny);
    }
}
