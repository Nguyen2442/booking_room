package com.company.booking.common;

import java.util.Set;
import org.springframework.data.domain.Pageable;

/** Only whitelisted properties may be sorted on: no 500s on unknown fields, no probing of hidden columns. */
public final class SortGuard {

    private SortGuard() {}

    public static void check(Pageable pageable, Set<String> allowed) {
        pageable.getSort().forEach(order -> {
            if (!allowed.contains(order.getProperty())) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_SORT",
                        "Cannot sort by '" + order.getProperty() + "'. Allowed: " + allowed);
            }
        });
    }
}
