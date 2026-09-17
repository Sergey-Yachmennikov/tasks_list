package com.example.tasklist.order.web;

import com.example.tasklist.order.service.exception.InvalidOrderRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OrderExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderExceptionHandler.class);

    @ExceptionHandler(InvalidOrderRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidOrderRequestException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
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
