package com.basir.inventory.web.license;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Locks every shop API while the license is missing, expired or the clock was set back — the web
 * equivalent of the phone's full-screen lock. The license endpoints stay open.
 */
@Component
public class LicenseInterceptor implements HandlerInterceptor {
    private final LicenseService license;

    public LicenseInterceptor(LicenseService license) {
        this.license = license;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        LicenseService.Status status = license.refresh();
        if (status.active()) return true;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"LICENSE\",\"state\":\"" + status.state() + "\"}");
        return false;
    }
}
