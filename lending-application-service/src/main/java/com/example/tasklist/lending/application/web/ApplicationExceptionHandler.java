package com.example.tasklist.lending.application.web;

import com.example.tasklist.lending.application.service.exception.ApplicationNotFoundException;
import com.example.tasklist.lending.application.service.exception.InvalidApplicationStatusException;
import com.example.tasklist.lending.application.service.exception.LenderBlockingException;
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

    @ExceptionHandler(InvalidApplicationStatusException.class)
    public ProblemDetail handleInvalidStatus(InvalidApplicationStatusException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(LenderBlockingException.class)
    public ProblemDetail handleLenderFailure(LenderBlockingException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Lender system unavailable");
        return problem;
    }

    /**
     * Catch-all so an unanticipated exception still comes back as our ProblemDetail contract
     * instead of Spring's default error page/body — and, critically, without leaking
     * {@code ex.getMessage()} (which for an exception we don't control might contain SQL,
     * stack details, or other internals) to the client. Full details still go to the logs,
     * correlated via the request id set by {@link CorrelationIdFilter}.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }
}
