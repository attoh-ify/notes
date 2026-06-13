package com.crowninteractive.notes.dto.attribution;

import com.crowninteractive.notes.dto.ot.Delta;
import java.util.List;

public record AuditProjection(
        Delta baseDelta,
        Delta visualDelta,
        List<FormatChangeItem> formatChanges,
        List<BlockFormatChangeItem> blockFormatChanges
) {}