package in.yograj.learnspringboot.railway_booking.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorizationHeader = request.getHeader("Authorization");
        boolean bearerHeader = authorizationHeader != null && authorizationHeader.startsWith("Bearer ");
        logger.debug("JWT diagnostic: method={} path={} authorizationHeaderPresent={} bearerHeader={}",
                request.getMethod(), request.getRequestURI(), authorizationHeader != null, bearerHeader);

        if (bearerHeader) {
            String token = authorizationHeader.substring(7);
            String stage = "access-token username extraction";

            try {
                String username = jwtService.extractUsernameFromAccessToken(token);
                logger.debug("JWT diagnostic: token username extraction succeeded; username={}", username);

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    logger.debug("JWT diagnostic: user lookup succeeded; username={}", username);

                    stage = "access-token validation";
                    boolean valid = jwtService.isAccessTokenValid(token, userDetails);
                    logger.debug("JWT diagnostic: token validity={}; username={}", valid, username);
                    if (valid) {
                        UsernamePasswordAuthenticationToken authenticationToken =
                                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                        authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                        logger.debug("JWT diagnostic: authentication placed in SecurityContext; username={} authorities={}",
                                username, userDetails.getAuthorities());
                    }
                } else if (username == null) {
                    logger.debug("JWT diagnostic: token username extraction returned null");
                } else {
                    logger.debug("JWT diagnostic: SecurityContext already contained authentication");
                }
            } catch (Exception exception) {
                SecurityContextHolder.clearContext();
                logger.warn("JWT diagnostic: failure during {}; exceptionType={}",
                        stage, exception.getClass().getName());
            }
        }

        filterChain.doFilter(request, response);
    }
}
