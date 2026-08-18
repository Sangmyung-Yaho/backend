package com.sangmyungyaho.barocare.global.security.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import lombok.RequiredArgsConstructor;

@Component
public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        
        com.sangmyungyaho.barocare.global.exception.ErrorResponse errorResponse = com.sangmyungyaho.barocare.global.exception.ErrorResponse.of(com.sangmyungyaho.barocare.global.exception.ErrorCode.UNAUTHORIZED);
        String jsonResponse = objectMapper.writeValueAsString(errorResponse);
        response.getWriter().write(jsonResponse);
    }
}
