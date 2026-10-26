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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
    void changePasswordSignsOutOtherSessionsAndReturnsAFreshToken() throws Exception {
        User u = user("alice@company.local", Role.USER);
        String oldToken = bearer(u);
        mvc.perform(withJson(post("/api/v1/auth/change-password"), u, "{\"currentPassword\":\"bad\",\"newPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        String res = mvc.perform(withJson(post("/api/v1/auth/change-password"), u, "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String newToken = json.readTree(res).get("accessToken").asText();
        // the token that was used to change the password (or any other session) no longer works ...
        mvc.perform(get("/api/v1/auth/me").header("Authorization", oldToken)).andExpect(status().isUnauthorized());
        // ... the fresh one does, and so does logging in with the new password
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newToken)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(login("alice@company.local", "NewPassword1!")))
                .andExpect(status().isOk());
    }

    @Test
    void guessingTheCurrentPasswordWithAStolenTokenIsThrottled() throws Exception {
        User u = user("thief-target@company.local", Role.USER);
        for (int i = 0; i < 5; i++) {
            mvc.perform(withJson(post("/api/v1/auth/change-password"), u, "{\"currentPassword\":\"guess-" + i + "\",\"newPassword\":\"NewPassword1!\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(withJson(post("/api/v1/auth/change-password"), u, "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"NewPassword1!\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void deactivatingAUserEndsTheirSessionImmediately() throws Exception {
        User admin = user("admin@company.local", Role.ADMIN);
        User alice = user("alice@company.local", Role.USER);
        String aliceToken = bearer(alice);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", aliceToken)).andExpect(status().isOk()); // warms the cache
        mvc.perform(withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/users/" + alice.getId()), admin,
                "{\"fullName\":\"alice\",\"role\":\"USER\",\"active\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", aliceToken))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anAdminPasswordResetSignsTheUserOut() throws Exception {
        User admin = user("admin@company.local", Role.ADMIN);
        User alice = user("alice@company.local", Role.USER);
        String aliceToken = bearer(alice);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", aliceToken)).andExpect(status().isOk());
        mvc.perform(withJson(post("/api/v1/admin/users/" + alice.getId() + "/reset-password"), admin, "{\"newPassword\":\"Fresh12345!\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", aliceToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void aDemotedAdminLosesAdminRightsAtOnce() throws Exception {
        User boss = user("boss@company.local", Role.ADMIN);
        User other = user("other@company.local", Role.ADMIN);
        String otherToken = bearer(other);
        mvc.perform(get("/api/v1/admin/users").header("Authorization", otherToken)).andExpect(status().isOk());
        mvc.perform(withJson(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/users/" + other.getId()), boss,
                "{\"fullName\":\"other\",\"role\":\"USER\",\"active\":true}")).andExpect(status().isOk());
        // the old token (which says ADMIN) must not work any more
        mvc.perform(get("/api/v1/admin/users").header("Authorization", otherToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void thereIsAlwaysAtLeastOneActiveAdmin_evenWhenTwoAdminsDemoteEachOtherAtTheSameTime() throws Exception {
        User a = user("a@company.local", Role.ADMIN);
        User b = user("b@company.local", Role.ADMIN);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<String> aDemotesB = () -> demote(go, a, b);
        java.util.concurrent.Callable<String> bDemotesA = () -> demote(go, b, a);
        var f1 = pool.submit(aDemotesB);
        var f2 = pool.submit(bDemotesA);
        go.countDown();
        java.util.List<String> outcomes = java.util.List.of(f1.get(), f2.get());
        pool.shutdownNow();
        org.assertj.core.api.Assertions.assertThat(outcomes).containsExactlyInAnyOrder("OK", "LAST_ADMIN");
        org.assertj.core.api.Assertions.assertThat(users.countByRoleAndActive(Role.ADMIN, true)).isEqualTo(1);
    }

    @org.springframework.beans.factory.annotation.Autowired com.company.booking.user.UserService userService;

    private String demote(java.util.concurrent.CountDownLatch go, User actor, User target) throws Exception {
        go.await();
        try {
            userService.update(actor.getId(), target.getId(), new com.company.booking.user.UserDtos.UpdateUserRequest("x", Role.USER, true));
            return "OK";
        } catch (com.company.booking.common.ApiException e) {
            return e.getCode();
        }
    }
}
