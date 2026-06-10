package com.example.notes.dto.attribution;

import com.example.notes.dto.ot.Delta;
import java.util.List;

public record AuditProjection(
        Delta baseDelta,
        Delta visualDelta,
        List<FormatChangeItem> formatChanges,
        List<BlockFormatChangeItem> blockFormatChanges
) {}