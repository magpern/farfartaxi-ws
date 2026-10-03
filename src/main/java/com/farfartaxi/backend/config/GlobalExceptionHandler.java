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

    /** Framework errors that already carry an HTTP status (404 no resource, 405, 415, missing params...). */
    @ExceptionHandler({
        org.springframework.web.servlet.resource.NoResourceFoundException.class,
        org.springframework.web.HttpRequestMethodNotSupportedException.class,
        org.springframework.web.HttpMediaTypeNotSupportedException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.ErrorResponseException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, String>> onFrameworkError(Exception ex, HttpServletRequest request) {
        int status = ex instanceof org.springframework.web.ErrorResponse er ? er.getStatusCode().value() : 400;
        log.warn("Request error {} {} {}: {}", request.getMethod(), request.getRequestURI(), status, ex.getMessage());
        String message = status == 404 ? "Not found" : "Bad request";
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> onUnreadable(Exception ex, HttpServletRequest request) {
        log.warn("Unreadable request body {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed request body"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onUnhandled(Exception ex, HttpServletRequest request) {
        log.error("Unhandled server error {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.internalServerError().body(Map.of("error", "Internal error"));
    }
}
