package com.raikiservice.raf.auth.security;

import java.io.IOException;

import com.raikiservice.raf.common.error.ApiError;
import com.raikiservice.raf.common.error.ApiException;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            UserPrincipal principal = jwtService.parse(header.substring(PREFIX.length()));
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ApiException e) {
            // Un jeton present mais invalide doit etre signale explicitement,
            // sinon le frontend ne sait pas qu'il doit rafraichir.
            SecurityContextHolder.clearContext();
            writeError(response, request, e);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, HttpServletRequest request, ApiException e)
            throws IOException {
        response.setStatus(e.getErrorCode().getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiError.of(e.getErrorCode(), e.getMessage(), request.getRequestURI()));
    }
}
