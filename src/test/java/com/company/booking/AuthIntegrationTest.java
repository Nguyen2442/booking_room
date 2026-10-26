package com.company.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.booking.user.Role;
import com.company.booking.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AuthIntegrationTest extends IntegrationTestBase {

    private String login(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void loginReturnsTokenAndUser() throws Exception {
        user("alice@company.local", Role.USER);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(login("Alice@Company.local", PASSWORD))) // email is case-insensitive
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("alice@company.local"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void wrongPasswordAndUnknownUserGiveSameError() throws Exception {
        user("alice@company.local", Role.USER);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("alice@company.local", "nope-nope")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("ghost@company.local", "nope-nope")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void disabledAccountCannotLogin() throws Exception {
        User u = user("alice@company.local", Role.USER);
        u.setActive(false);
        users.save(u);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("alice@company.local", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedFailuresAreThrottled() throws Exception {
        // unique email: the throttle is in-memory and shared by the whole test context
        user("locked@company.local", Role.USER);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("locked@company.local", "wrong-wrong")))
                    .andExpect(status().isUnauthorized());
        }
        // even the right password is refused while locked
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("locked@company.local", PASSWORD)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        // ... but another account from the same IP is not affected
        user("other@company.local", Role.USER);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("other@company.local", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointsRequireValidToken() throws Exception {
        mvc.perform(get("/api/v1/rooms")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/rooms").header("Authorization", "Bearer garbage.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        User u = user("alice@company.local", Role.USER);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(u)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("alice@company.local"));
    }

    @Test
    void regularUserCannotUseAdminEndpoints() throws Exception {
        User u = user("alice@company.local", Role.USER);
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(u)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminManagesUsers() throws Exception {
        User admin = user("admin@company.local", Role.ADMIN);
        String body = "{\"email\":\"New.User@company.local\",\"fullName\":\"New User\",\"password\":\"Password123!\",\"role\":\"USER\"}";
        mvc.perform(withJson(post("/api/v1/admin/users"), admin, body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("new.user@company.local"));
        mvc.perform(withJson(post("/api/v1/admin/users"), admin, body.replace("New.User", "new.USER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
        mvc.perform(withJson(post("/api/v1/admin/users"), admin, body.replace("Password123!", "short")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        mvc.perform(get("/api/v1/admin/users?q=new.user").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void adminCannotDeactivateSelf() throws Exception {
        User admin = user("admin@company.local", Role.ADMIN);
        String body = "{\"fullName\":\"Admin\",\"role\":\"ADMIN\",\"active\":false}";
        mvc.perform(withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/users/" + admin.getId()), admin, body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CANNOT_MODIFY_SELF"));
    }

    @Test
    void passwordLongerThan72BytesIsRejectedNotTruncated() throws Exception {
        User admin = user("admin@company.local", Role.ADMIN);
        String longPw = "á".repeat(40); // 40 chars but 80 bytes
        String body = "{\"email\":\"x@company.local\",\"fullName\":\"X\",\"password\":\"" + longPw + "\",\"role\":\"USER\"}";
        mvc.perform(withJson(post("/api/v1/admin/users"), admin, body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
    }

    @Test
    void changePassword() throws Exception {
        User u = user("alice@company.local", Role.USER);
        mvc.perform(withJson(post("/api/v1/auth/change-password"), u, "{\"currentPassword\":\"bad\",\"newPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        mvc.perform(withJson(post("/api/v1/auth/change-password"), u, "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("alice@company.local", "NewPassword1!")))
                .andExpect(status().isOk());
    }
}
