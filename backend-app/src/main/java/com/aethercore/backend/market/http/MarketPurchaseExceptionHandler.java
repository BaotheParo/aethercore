package com.aethercore.backend.market.http;

import com.aethercore.backend.market.idempotency.IdempotencyConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller advice handling domain and validation exceptions with stable error codes:
 * 400 -> INVALID_REQUEST
 * 404 -> PURCHASE_REQUEST_NOT_FOUND
 * 409 -> IDEMPOTENCY_CONFLICT
 */
@RestControllerAdvice
public class MarketPurchaseExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IdempotencyConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "IDEMPOTENCY_CONFLICT");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(PurchaseRequestNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(PurchaseRequestNotFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "PURCHASE_REQUEST_NOT_FOUND");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        String detailMessage = ex.getMessage();
        if (ex instanceof MethodArgumentNotValidException manve) {
            detailMessage = manve.getBindingResult().getAllErrors().stream()
                    .findFirst()
                    .map(err -> err.getDefaultMessage())
                    .orElse("Validation failed");
        } else if (ex instanceof HttpMessageNotReadableException) {
            detailMessage = "Malformed JSON request body or invalid property type";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INVALID_REQUEST");
        body.put("message", detailMessage != null ? detailMessage : "Invalid request payload or parameters");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
