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
class ExternalUserCreateEndpointsTest extends AccessTestSupport {

    @Test
    void usersEndpointsCreateExternalWithoutPassword() {
        ensureUser("support1", "support1@mnemosyne-systems.ai", User.TYPE_SUPPORT, "support1");
        ensureUser("tam1", "tam1@mnemosyne-systems.ai", User.TYPE_TAM, "tam1");
        ensureUser("super1", "super1@mnemosyne-systems.ai", User.TYPE_SUPERUSER, "super1");
        ensureUser("admin1", "admin1@mnemosyne-systems.ai", User.TYPE_ADMIN, "admin1");
        Long companyId = ensureCompany("External Create Endpoints Co");
        ensureCompanyUsers(companyId, "support1@mnemosyne-systems.ai", "tam1@mnemosyne-systems.ai",
                "super1@mnemosyne-systems.ai", "admin1@mnemosyne-systems.ai");

        checkCreate("/support/users", login("support1", "support1"), companyId);
        checkCreate("/tam/users", login("tam1", "tam1"), companyId);
        checkCreate("/superuser/users", login("super1", "super1"), companyId);
        checkCreate("/users", login("admin1", "admin1"), companyId);
    }

    void checkCreate(String path, String cookie, Long companyId) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "users-endpoint-" + suffix + "@mnemosyne-systems.ai";

        RestAssured.given().redirects().follow(false).cookie(AuthHelper.AUTH_COOKIE, cookie)
                .contentType(ContentType.URLENC).formParam("name", "extprobe" + suffix)
                .formParam("fullName", "Endpoint External").formParam("email", email).formParam("type", "external")
                .formParam("companyId", companyId).post(path).then().statusCode(303);

        User created = User.find("email", email).firstResult();
        Assertions.assertNotNull(created);
        Assertions.assertEquals(User.TYPE_EXTERNAL, created.type);
        Assertions.assertEquals(User.DISABLED_PASSWORD_HASH, created.passwordHash);
    }
}
