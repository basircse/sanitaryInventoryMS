package com.basir.inventory.web.api;

import com.basir.inventory.web.crypto.BackupCrypto.BackupFormatException;
import com.basir.inventory.web.inventory.InventoryException;
import com.basir.inventory.web.license.LicenseService.ActivationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;
import java.util.Map;

/** Errors as {"error": CODE}; the browser maps codes to Bangla/English messages. */
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(InventoryException.class)
    ResponseEntity<Map<String, String>> inventory(InventoryException e) {
        HttpStatus status = e.reason == InventoryException.Reason.NOT_FOUND ? HttpStatus.NOT_FOUND
                : e.reason == InventoryException.Reason.BAD_INPUT || e.reason == InventoryException.Reason.BAD_QTY ? HttpStatus.BAD_REQUEST
                : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of("error", e.reason.name()));
    }

    @ExceptionHandler(ActivationException.class)
    ResponseEntity<Map<String, String>> activation(ActivationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.error.name()));
    }

    @ExceptionHandler(BackupFormatException.class)
    ResponseEntity<Map<String, String>> backup(BackupFormatException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "BACKUP", "message", e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, DateTimeParseException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, String>> badInput(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_INPUT"));
    }
}
