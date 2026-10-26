package com.company.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.booking.user.Role;
import org.junit.jupiter.api.Test;

class ConfigIntegrationTest extends IntegrationTestBase {

    @Test
    void exposesBookingRulesToAuthenticatedClients() throws Exception {
        var u = user("alice@company.local", Role.USER);
        mvc.perform(get("/api/v1/config").header("Authorization", bearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.workdayStart").value("08:00"))
                .andExpect(jsonPath("$.workdayEnd").value("20:00"))
                .andExpect(jsonPath("$.slotMinutes").value(15));
        mvc.perform(get("/api/v1/config")).andExpect(status().isUnauthorized());
    }
}
