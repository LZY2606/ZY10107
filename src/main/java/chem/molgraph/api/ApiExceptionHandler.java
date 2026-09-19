package chem.molgraph.api;

import chem.molgraph.store.ConflictException;
import chem.molgraph.store.EventStoreException;
import chem.molgraph.store.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflictState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("ILLEGAL_STATE", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> versionConflict(ConflictException e) {
        Map<String, Object> body = body("VERSION_CONFLICT", e.getMessage());
        body.put("expectedVersion", e.expectedVersion);
        body.put("actualVersion", e.actualVersion);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(EventStoreException.class)
    public ResponseEntity<Map<String, Object>> storeFailure(EventStoreException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body("STORE_FAILURE", e.getMessage()));
    }

    private Map<String, Object> body(String code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("errorCode", code);
        map.put("message", message);
        return map;
    }
}
