package ru.codesraider.smartsearch.web;

import java.io.IOException;
import java.sql.SQLException;

import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<Problem> problem(ApiProblem exception) {
        if (exception.getCause() != null) {
            LoggerFactory.getLogger(ApiExceptionHandler.class).warn(exception.code(), exception.getCause());
        }
        var body = new Problem("about:blank", exception.status().getReasonPhrase(), exception.status().value(),
                exception.getMessage(), exception.code());
        return ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingRequestValueException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Problem> invalid() {
        return problem(ApiProblem.invalid("Проверьте параметры запроса и формат тела."));
    }

    @ExceptionHandler({SQLException.class, IOException.class})
    ResponseEntity<Problem> catalog(Exception failure) {
        return problem(ApiProblem.catalog(failure));
    }

    record Problem(String type, String title, int status, String detail, String code) {}
}
