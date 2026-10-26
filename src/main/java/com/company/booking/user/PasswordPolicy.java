package com.company.booking.user;

import com.company.booking.common.ApiException;
import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {

    private static final int BCRYPT_MAX_BYTES = 72;

    private PasswordPolicy() {}

    /**
     * BCrypt silently ignores everything past 72 bytes. Characters such as Vietnamese letters take 2-3 bytes,
     * so a length limit in characters is not enough; reject instead of truncating.
     */
    public static void check(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw ApiException.unprocessable("PASSWORD_TOO_LONG", "Password must be at most 72 bytes in UTF-8");
        }
    }
}
