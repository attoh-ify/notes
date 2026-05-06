package com.example.notes.controllers;

import com.example.notes.dto.response.ResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/health")
@Tag(
        name = "Health check",
        description = "Simple health check"
)
public class HealthCheckController {
    @GetMapping("/")
    @Operation(
            summary = "Health check"
    )
    public ResponseDto healthCheck() {
        return new ResponseDto("Service is active");
    }
}
