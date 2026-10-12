package com.finsight.config;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(org.springframework.web.bind.MethodArgumentNotValidException exception) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", error.getDefaultMessage() == null ? "입력값을 확인해 주세요." : error.getDefaultMessage()))
                .toList();
        String message = errors.isEmpty() ? "입력값을 확인해 주세요." : errors.getFirst().get("message");
        return ResponseEntity.badRequest().body(Map.of("message", message, "errors", errors));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatusException(ResponseStatusException exception) {
        String message = exception.getReason();
        if (message == null || message.isBlank()) {
            message = "요청을 처리하지 못했습니다.";
        }
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
    }
}
