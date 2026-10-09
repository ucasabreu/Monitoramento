package com.example.monitoramento.api;

import com.example.monitoramento.catalog.ResourceConflict;
import com.example.monitoramento.catalog.ResourceMissing;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CatalogExceptionHandler {
    @ExceptionHandler(ResourceMissing.class)
    public ProblemDetail missing(ResourceMissing error) { return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, error.getMessage()); }
    @ExceptionHandler(ResourceConflict.class)
    public ProblemDetail conflict(ResourceConflict error) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage()); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail integrity() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Nome já cadastrado, referência inválida ou recurso ainda associado a circuitos.");
    }
}
