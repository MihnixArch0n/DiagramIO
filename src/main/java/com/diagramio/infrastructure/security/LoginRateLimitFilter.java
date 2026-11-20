package com.diagramio.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

public final class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(LoginRateLimitFilter.class);
    private final RedisLoginRateLimiter limiter;
    private final ErrorWriter errorWriter;

    public LoginRateLimitFilter(RedisLoginRateLimiter limiter, ErrorWriter errorWriter) {
        this.limiter = limiter;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !(request.getContextPath() + "/api/v1/auth/login").equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfter;
        try {
            retryAfter = limiter.retryAfterSeconds(request.getRemoteAddr());
        } catch (RuntimeException exception) {
            LOG.warn("Login rate limit storage unavailable", exception);
            errorWriter.write(request, response, 503, "Login temporarily unavailable");
            return;
        }
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            errorWriter.write(request, response, 429, "Too many login attempts. Please try again later.");
            return;
        }
        chain.doFilter(request, response);
    }

    @FunctionalInterface
    public interface ErrorWriter {
        void write(HttpServletRequest request, HttpServletResponse response, int status, String message)
                throws IOException;
    }
}
