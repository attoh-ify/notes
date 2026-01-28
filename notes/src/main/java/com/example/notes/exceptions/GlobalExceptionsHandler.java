package com.example.notes.exceptions;

import com.example.notes.dto.response.ResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionsHandler {
    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionsHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ResponseDto> handleNotFound(NotFoundException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ResponseDto(false, ex.getMessage(), null));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ResponseDto> handleBadRequest(BadRequestException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ResponseDto(false, ex.getMessage(), null));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ResponseDto> handleForbidden(ForbiddenException ex) {
        log.warn(ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ResponseDto(false, ex.getMessage(), null));
    }

    /**
     * Catch-all (REAL errors)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDto> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex); // full stack trace
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ResponseDto(
                        false,
                        "Something went wrong. Please try again later.",
                        null
                ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleNoResourceFound(NoResourceFoundException ex) {
        // Intentionally ignored (browser probes, favicon, devtools, etc.)
    }
}
