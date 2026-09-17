package com.example.tasklist.kyc.application.web;

import com.example.tasklist.kyc.application.service.exception.ApplicationClientMismatchException;
import com.example.tasklist.kyc.application.service.exception.ApplicationNotFoundException;
import com.example.tasklist.kyc.application.service.exception.InvalidApplicationStatusException;
import com.example.tasklist.kyc.application.service.exception.KycNotCompletedException;
import com.example.tasklist.kyc.application.service.exception.NotificationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApplicationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApplicationExceptionHandler.class);

    @ExceptionHandler(ApplicationNotFoundException.class)
    public ProblemDetail handleNotFound(ApplicationNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ApplicationClientMismatchException.class)
    public ProblemDetail handleClientMismatch(ApplicationClientMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(InvalidApplicationStatusException.class)
    public ProblemDetail handleInvalidStatus(InvalidApplicationStatusException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(KycNotCompletedException.class)
    public ProblemDetail handleKycNotCompleted(KycNotCompletedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(NotificationFailedException.class)
    public ProblemDetail handleNotificationFailure(NotificationFailedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Notification gateway unavailable");
        return problem;
    }

    /**
     * Catch-all, чтобы непредвиденное исключение всё равно возвращалось в нашем контракте
     * ProblemDetail, а не в дефолтной странице/теле ошибки от Spring — и, что важно, без утечки
     * {@code ex.getMessage()} клиенту (для неконтролируемого нами исключения там может быть SQL,
     * детали стектрейса или другие внутренности). Полные детали всё равно идут в логи,
     * коррелированные через request id, который проставляет {@link CorrelationIdFilter}.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }
}
