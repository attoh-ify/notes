package com.crowninteractive.notes.dto.attribution;

import com.crowninteractive.notes.dto.ot.Delta;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AuditProjection {
    private Delta baseDelta;
    private Delta visualDelta;
    private List<FormatChangeItem> formatChanges;
    private List<BlockFormatChangeItem> blockFormatChanges;
}