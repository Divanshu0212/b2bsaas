package com.salespipe.identity.api;

import com.salespipe.identity.infra.RefreshTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler({RefreshTokenService.TokenReuseException.class,
                       RefreshTokenService.InvalidTokenException.class})
    public ProblemDetail onToken(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setTitle("Authentication failed");
        pd.setDetail(ex.getMessage());
        return pd;
    }
}
