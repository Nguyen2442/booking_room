package com.company.booking.common;

import com.company.booking.security.AuthUser;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * One structured line per sensitive action ("who did what to whom"), under the dedicated logger {@code AUDIT} so a
 * log pipeline can route it separately. The actor is read from the security context, so call sites stay simple.
 */
public final class AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger("AUDIT");

    private AuditLog() {}

    public static void record(String action, Map<String, ?> details) {
        String actor = "system";
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser u) {
            actor = u.email() + "(" + u.id() + ")";
        }
        LOG.info("action={} actor={} {}", action, clean(actor),
                details.entrySet().stream().map(e -> e.getKey() + "=" + clean(String.valueOf(e.getValue())))
                        .collect(Collectors.joining(" ")));
    }

    /** User-controlled text (emails, titles) must not be able to forge extra log lines. */
    public static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n\\t\\p{Cntrl}]+", " ").trim();
    }
}
