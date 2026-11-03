package reminders;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reads the X-Tenant header into TenantContext for the duration of the request. */
@Component
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenant = request.getHeader("X-Tenant");
        if (tenant != null && !tenant.isBlank()) TenantContext.set(tenant.trim());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear(); // server threads are pooled: the next request on this thread is someone else's
        }
    }
}
