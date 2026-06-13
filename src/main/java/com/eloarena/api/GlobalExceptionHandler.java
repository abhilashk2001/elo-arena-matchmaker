package com.eloarena.api;

import com.eloarena.match.InvalidWinnerException;
import com.eloarena.match.MatchNotFoundException;
import com.eloarena.matchmaking.AlreadyQueuedException;
import com.eloarena.matchmaking.NotQueuedException;
import com.eloarena.player.PlayerNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into the uniform ErrorResponse shape with the right status code.
 * Keeping this in one place means controllers stay free of error-mapping noise and every
 * client sees a consistent error body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PlayerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePlayerNotFound(PlayerNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(NotQueuedException.class)
    public ResponseEntity<ErrorResponse> handleNotQueued(NotQueuedException e) {
        return build(HttpStatus.NOT_FOUND, "NOT_QUEUED", e.getMessage());
    }

    @ExceptionHandler(AlreadyQueuedException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyQueued(AlreadyQueuedException e) {
        return build(HttpStatus.CONFLICT, "ALREADY_QUEUED", e.getMessage());
    }

    @ExceptionHandler(MatchNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMatchNotFound(MatchNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "MATCH_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(InvalidWinnerException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWinner(InvalidWinnerException e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_WINNER", e.getMessage());
    }

    /** Bean Validation failure on a request body (@Valid). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("Request validation failed.");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    /** Bean Validation failure on a request parameter (@Validated controller). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(ConstraintViolationException e) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", e.getMessage());
    }

    /** Bad input from application code, e.g. an unknown matcher strategy name. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}
