package chem.molgraph.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Bond(String a, String b, int order, BondStereo stereo) {
}
