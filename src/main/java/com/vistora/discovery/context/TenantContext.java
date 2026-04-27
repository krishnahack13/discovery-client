package com.vistora.discovery.context;


import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * ThreadLocal holder for tenant information during request processing.
 * This context is set by JwtFilter and used throughout the application for dynamic schema resolution.
 */
public class TenantContext {

    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);
    private static final ThreadLocal<TenantInfo> TENANT_CONTEXT = new ThreadLocal<>();

    /**
     * Set tenant information for the current request thread
     */
    public static void setTenantInfo(Integer tenantId, String tenantName) {
        TenantInfo info = new TenantInfo(tenantId, tenantName);
        TENANT_CONTEXT.set(info);
        log.debug("Tenant context set: tenantId={}, tenantName={}", tenantId, tenantName);
    }

    /**
     * Get tenant ID from current context
     */
    public static Integer getTenantId() {
        TenantInfo info = TENANT_CONTEXT.get();
        return info != null ? info.getTenantId() : null;
    }

    /**
     * Get tenant name from current context
     */
    public static String getTenantName() {
        TenantInfo info = TENANT_CONTEXT.get();
        return info != null ? info.getTenantName() : null;
    }

    /**
     * Get full tenant info from current context
     */
    public static TenantInfo getTenantInfo() {
        return TENANT_CONTEXT.get();
    }

    /**
     * Clear tenant context - MUST be called after request processing
     */
    public static void clear() {
        TENANT_CONTEXT.remove();
        log.debug("Tenant context cleared");
    }

    /**
     * Inner class to hold tenant information
     */
    public static class TenantInfo {
        private final Integer tenantId;
        private final String tenantName;

        public TenantInfo(Integer tenantId, String tenantName) {
            this.tenantId = tenantId;
            this.tenantName = tenantName;
        }

        public Integer getTenantId() {
            return tenantId;
        }

        public String getTenantName() {
            return tenantName;
        }
    }
}

