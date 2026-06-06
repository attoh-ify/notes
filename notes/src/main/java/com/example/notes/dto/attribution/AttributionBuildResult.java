package com.example.notes.dto.attribution;

public record AttributionBuildResult(
        ReviewProjection projection,
        boolean revisionLogChanged
) {}