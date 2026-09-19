package com.molmap.web;

import com.molmap.service.BadRequestException;
import com.molmap.service.ConflictRuntimeException;
import com.molmap.service.NotFoundException;
import com.molmap.store.CaseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", e.getMessage(), 404, null));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> bad(BadRequestException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("BAD_REQUEST", e.getMessage(), 400, null));
    }

    @ExceptionHandler(ConflictRuntimeException.class)
    public ResponseEntity<ApiError> businessConflict(ConflictRuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(e.code(), e.getMessage(), 409, null));
    }

    @ExceptionHandler(CaseRepository.Conflict.class)
    public ResponseEntity<ApiError> optimistic(CaseRepository.Conflict e) {
        // The late browser receives both versions so the UI can show the winner
        // and offer a re-merge.
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                "CASE_VERSION_CONFLICT", e.getMessage(), 409,
                Map.of("expected", e.expected, "actual", e.actual)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegal(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_JSON", e.getMessage(), 400, null));
    }
}
