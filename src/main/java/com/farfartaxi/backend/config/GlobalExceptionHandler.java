package com.farfartaxi.backend.config;

import com.farfartaxi.backend.service.AppException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, String>> onAppException(AppException ex, HttpServletRequest request) {
        log.warn("API error {} {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getStatus().value(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(Map.of("error", ex.getMessage()));
    }

    /** Method-security denials (@PreAuthorize) must be 403, not the generic 500 below. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> onAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Method security 403 {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> onValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        FieldError fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = fieldError == null ? "Validation failed" : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        log.warn("Validation failed {} {}: {}", request.getMethod(), request.getRequestURI(), message);
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /** Lost-update protection (UserEntity @Version): the client may simply retry. */
    @ExceptionHandler({org.springframework.orm.ObjectOptimisticLockingFailureException.class,
        jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<Map<String, String>> onOptimisticLock(Exception ex, HttpServletRequest request) {
        log.warn("Optimistic lock conflict {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(409).body(Map.of("error", "Account was changed concurrently, please retry"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onUnhandled(Exception ex, HttpServletRequest request) {
        log.error("Unhandled server error {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.internalServerError().body(Map.of("error", "Internal error"));
    }
}
