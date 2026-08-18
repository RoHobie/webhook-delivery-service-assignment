package com.webhook.delivery.security;

import com.webhook.delivery.repository.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final TenantRepository tenantRepository;
    private final boolean enforceDbCheck;

    public TenantInterceptor(
            TenantRepository tenantRepository,
            @Value("${app.tenant-validation.enforce-db-check:false}") boolean enforceDbCheck) {
        this.tenantRepository = tenantRepository;
        this.enforceDbCheck = enforceDbCheck;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.trim().isEmpty()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Missing or empty " + TENANT_HEADER + " header\"}");
            return false;
        }

        String cleanedTenantId = tenantId.trim();

        if (enforceDbCheck && !tenantRepository.existsById(cleanedTenantId)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Unauthorized: Tenant ID does not exist: " + cleanedTenantId + "\"}");
            return false;
        }

        TenantContext.setTenantId(cleanedTenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
