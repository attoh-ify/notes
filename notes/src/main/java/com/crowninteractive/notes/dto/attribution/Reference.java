package com.crowninteractive.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Reference {
    private int reviewStart;
    private int componentStart;
    private int length;
    String opId;
    Integer componentIndex;
}