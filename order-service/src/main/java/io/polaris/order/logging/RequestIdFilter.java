package io.polaris.order.logging;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.polaris.order.config.ObservabilityConstants;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = requestId(request);
        String previous = ObservabilityConstants.currentRequestId();

        try {
            MDC.put(ObservabilityConstants.REQUEST_ID_MDC_KEY, requestId);
            response.setHeader(ObservabilityConstants.REQUEST_ID_HEADER, requestId);
            filterChain.doFilter(request, response);
        } finally {
            if (ObservabilityConstants.hasText(previous)) {
                MDC.put(ObservabilityConstants.REQUEST_ID_MDC_KEY, previous);
            } else {
                MDC.remove(ObservabilityConstants.REQUEST_ID_MDC_KEY);
            }
        }
    }

    private String requestId(HttpServletRequest request) {
        String provided = request.getHeader(ObservabilityConstants.REQUEST_ID_HEADER);
        if (ObservabilityConstants.hasText(provided)) {
            return provided;
        }
        return UUID.randomUUID().toString();
    }
}
