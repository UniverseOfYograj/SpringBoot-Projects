package in.yograj.learnspringboot.railway_booking.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Filter that establishes and propagates a correlation/request ID for every HTTP request.
 *
 * Requirements:
 * 1. Checks incoming "X-Request-Id" header.
 * 2. If present and matching a strict safe format (1-64 alphanumeric, dash, or underscore characters),
 *    the incoming ID is retained.
 * 3. If missing, blank, oversized, or containing unsafe/malformed characters (e.g. newline injection,
 *    format string tokens, or non-alphanumeric symbols), a secure random UUID is generated.
 * 4. Puts the correlation ID into SLF4J MDC under key "correlationId".
 * 5. Attaches the "X-Request-Id" header to the HTTP response.
 * 6. Always clears the MDC entry in a finally block to prevent thread-pool pollution.
 * 7. Supports async and error dispatches safely.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "correlationId";
    public static final String CORRELATION_ID_ATTRIBUTE = "in.yograj.correlationId";

    // Strictly allows only alphanumeric characters, hyphens, and underscores up to 64 chars
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = (String) request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
            request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Resolves a sanitized, safe correlation ID. Replaces invalid or malformed values with a UUID.
     */
    public static String resolveCorrelationId(String candidate) {
        if (candidate != null) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty() && SAFE_ID_PATTERN.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }
}
