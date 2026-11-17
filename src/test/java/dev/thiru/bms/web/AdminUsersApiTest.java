package dev.thiru.bms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.jolt.test.JoltTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Users, roles and theatre owner links under /api/admin. */
class AdminUsersApiTest {

    @RegisterExtension final WebKit kit = new WebKit();
    private final String admin = kit.admin();

    private JoltTest.Call call(String method, String path) {
        return kit.http.call(method, path).header("Authorization", WebKit.bearer(admin));
    }

    @Test
    void usersAreListedWithRolesAndCinemasAndSearchable() {
        kit.user("ada@example.com");
        kit.user("bob@example.com");
        long ada = kit.idOf("ada@example.com");
        call("PUT", "/api/admin/cinemas/2/owners/" + ada).expect(200);
        call("GET", "/api/admin/users?size=2")
                .expect(200)
                .jsonEquals("total", 3)
                .jsonEquals("totalPages", 2)
                .jsonEquals("items.0.email", "admin@bms.test")
                .jsonEquals("items.0.roles.0", "ADMIN")
                .jsonEquals("items.1.email", "ada@example.com")
                .jsonEquals("items.1.roles.0", "AUDIENCE")
                .jsonEquals("items.1.roles.1", "THEATRE_OWNER")
                .jsonEquals("items.1.cinemaIds.0", 2);
        call("GET", "/api/admin/users?q=BOB")
                .expect(200)
                .jsonEquals("total", 1)
                .jsonEquals("items.0.email", "bob@example.com");
    }

    @Test
    void grantingAndRevokingTheatreOwner() {
        kit.user("ada@example.com");
        long ada = kit.idOf("ada@example.com");
        call("PUT", "/api/admin/users/" + ada + "/roles/theatre_owner")
                .expect(200)
                .jsonEquals("roles.1", "THEATRE_OWNER");
        // The new role reaches the token at the next sign-in or refresh.
        String owner = kit.login("ada@example.com");
        kit.http
                .get("/api/owner/cinemas")
                .header("Authorization", WebKit.bearer(owner))
                .expect(200);
        call("PUT", "/api/admin/cinemas/2/owners/" + ada).expect(200).jsonEquals("cinemaIds.0", 2);

        JoltTest.Result revoked =
                call("DELETE", "/api/admin/users/" + ada + "/roles/THEATRE_OWNER").expect(200);
        assertEquals(List.of("AUDIENCE"), revoked.jsonField("roles"));
        assertEquals(List.of(), revoked.jsonField("cinemaIds"));
        kit.http
                .get("/api/owner/cinemas")
                .header("Authorization", WebKit.bearer(kit.login("ada@example.com")))
                .expect(403);
    }

    @Test
    void errorsForBadRolesUnknownIdsAndAudience() {
        kit.user("ada@example.com");
        long ada = kit.idOf("ada@example.com");
        call("PUT", "/api/admin/users/" + ada + "/roles/ROOT")
                .expect(422)
                .jsonEquals("code", "VALIDATION");
        call("DELETE", "/api/admin/users/" + ada + "/roles/AUDIENCE").expect(422);
        call("PUT", "/api/admin/users/999/roles/THEATRE_OWNER").expect(404);
        call("PUT", "/api/admin/cinemas/999/owners/" + ada).expect(404);
        call("PUT", "/api/admin/cinemas/2/owners/999").expect(404);
        call("DELETE", "/api/admin/cinemas/999/owners/" + ada).expect(404);
    }

    @Test
    void anAdminMayDemoteAnotherAdmin() {
        kit.user("ada@example.com");
        long ada = kit.idOf("ada@example.com");
        call("PUT", "/api/admin/users/" + ada + "/roles/ADMIN").expect(200);
        kit.http
                .get("/api/admin/stats")
                .header("Authorization", WebKit.bearer(kit.login("ada@example.com")))
                .expect(200);
        call("DELETE", "/api/admin/users/" + ada + "/roles/ADMIN")
                .expect(200)
                .jsonEquals("roles.0", "AUDIENCE");
    }
}
