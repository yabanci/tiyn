package kz.tiyn.task.tasks.error;

import kz.tiyn.platform.web.ErrorBody;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "kz.tiyn.task")
class TaskExceptionHandler {

    @ExceptionHandler(TaskException.class)
    ResponseEntity<ErrorBody> handle(TaskException e) {
        return ResponseEntity.status(statusOf(e)).body(new ErrorBody(e.error().code()));
    }

    private static HttpStatus statusOf(TaskException e) {
        return switch (e.error()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case INVALID_TRANSITION, CONCURRENT_MODIFICATION, DEADLINE_PASSED -> HttpStatus.CONFLICT;
            case INVALID_REWARD,
                    INVALID_PENALTY,
                    INVALID_DEADLINE,
                    INVALID_TITLE,
                    INVALID_NOTE,
                    INVALID_MILESTONE,
                    INVALID_KIND,
                    UNSUPPORTED_PROOF,
                    INVALID_IDEMPOTENCY_KEY,
                    TEEN_REQUIRED,
                    INVALID_FILTER,
                    INVALID_SCHEDULE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MEDIA_BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
