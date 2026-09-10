/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne_systems.resource;

import ai.mnemosyne_systems.model.User;
import ai.mnemosyne_systems.util.AuthHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SupportExternalUserRoutingTest extends AccessTestSupport {

    @Test
    void supportExternalUserPostEndpointsDispatch() {
        ensureUser("support1", "support1@mnemosyne-systems.ai", User.TYPE_SUPPORT, "support1");
        Long companyId = ensureCompany("Support Externals Routing Co");
        ensureCompanyUsers(companyId, "support1@mnemosyne-systems.ai");
        String cookie = login("support1", "support1");
        String email = "routing-" + UUID.randomUUID() + "@mnemosyne-systems.ai";

        RestAssured.given().redirects().follow(false).cookie(AuthHelper.AUTH_COOKIE, cookie)
                .contentType(ContentType.URLENC).formParam("fullName", "Routing Target").formParam("email", email)
                .formParam("companyId", companyId).post("/support/externals").then().statusCode(303);
        User created = User.find("email", email).firstResult();
        Assertions.assertNotNull(created);

        RestAssured.given().redirects().follow(false).cookie(AuthHelper.AUTH_COOKIE, cookie)
                .contentType(ContentType.URLENC).formParam("fullName", "Routing Target").formParam("email", email)
                .post("/support/externals/" + created.id).then().statusCode(303);

        RestAssured.given().redirects().follow(false).cookie(AuthHelper.AUTH_COOKIE, cookie)
                .contentType(ContentType.URLENC).post("/support/externals/" + created.id + "/delete").then()
                .statusCode(303);
        Assertions.assertNull(refreshedUser(created.id));
    }

    @Test
    void supportExternalUserCreateRequiresCompany() {
        ensureUser("support1", "support1@mnemosyne-systems.ai", User.TYPE_SUPPORT, "support1");
        ensureCompany("Support Externals Routing Co");
        String cookie = login("support1", "support1");

        RestAssured.given().redirects().follow(false).cookie(AuthHelper.AUTH_COOKIE, cookie)
                .contentType(ContentType.URLENC).formParam("fullName", "Routing Target")
                .formParam("email", "routing-" + UUID.randomUUID() + "@mnemosyne-systems.ai").post("/support/externals")
                .then().statusCode(400);
    }

    @Test
    void otherRolesExternalUserPostEndpointsDispatch() {
        ensureUser("tam1", "tam1@mnemosyne-systems.ai", User.TYPE_TAM, "tam1");
        ensureUser("super1", "super1@mnemosyne-systems.ai", User.TYPE_SUPERUSER, "super1");
        ensureUser("plainuser1", "plainuser1@mnemosyne-systems.ai", User.TYPE_USER, "plainuser1");
        Long companyId = ensureCompany("Routing Externals Co");
        ensureCompanyUsers(companyId, "tam1@mnemosyne-systems.ai", "super1@mnemosyne-systems.ai",
                "plainuser1@mnemosyne-systems.ai");

        checkRoleCrud("tam", login("tam1", "tam1"), companyId);
        checkRoleCrud("superuser", login("super1", "super1"), companyId);
        checkRoleCrud("user", login("plainuser1", "plainuser1"), companyId);
    }

    void checkRoleCrud(String role, String cookie, Long companyId) {
        String email = "routing-" + role + "-" + UUID.randomUUID() + "@mnemosyne-systems.ai";

        RestAssured.given().cookie(AuthHelper.AUTH_COOKIE, cookie).get("/api/" + role + "/externals").then()
                .statusCode(200);

        RestAssured.given().redirects().follow(false).cookie(AuthHelper.AUTH_COOKIE, cookie)
                .contentType(ContentType.URLENC).formParam("fullName", "Routing Target").formParam("email", email)
                .formParam("companyId", companyId).post("/" + role + "/externals").then().statusCode(303);
        User created = User.find("email", email).firstResult();
        Assertions.assertNotNull(created);

        RestAssured.given().cookie(AuthHelper.AUTH_COOKIE, cookie).get("/api/" + role + "/externals/" + created.id)
                .then().statusCode(200);

        RestAssured.given().redirects().follow(false).cookie(AuthHelper.AUTH_COOKIE, cookie)
                .contentType(ContentType.URLENC).formParam("fullName", "Routing Target").formParam("email", email)
                .post("/" + role + "/externals/" + created.id).then().statusCode(303);

        RestAssured.given().redirects().follow(false).cookie(AuthHelper.AUTH_COOKIE, cookie)
                .contentType(ContentType.URLENC).post("/" + role + "/externals/" + created.id + "/delete").then()
                .statusCode(303);
        Assertions.assertNull(refreshedUser(created.id));
    }
}
