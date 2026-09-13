/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne_systems.resource;

import ai.mnemosyne_systems.model.Category;
import ai.mnemosyne_systems.model.Ticket;
import ai.mnemosyne_systems.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.RestAssured;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ResolutionReportTest extends AccessTestSupport {

    static final String REQUESTER_EMAIL = "rs-requester@mnemosyne-systems.ai";
    static final String SUPPORT_EMAIL = "rs-support@mnemosyne-systems.ai";

    @Transactional
    Long seedResolutionTicket(String companyName, String categoryName) {
        Long companyId = ensureCompany(companyName);
        Category category = ensureCategory(categoryName, categoryName + " description", false);
        Ticket ticket = ensureTicket(companyId);
        Ticket managed = Ticket.findById(ticket.id);
        managed.category = category;
        return managed.id;
    }

    @Transactional
    void ensureResolutionUsers() {
        ensureUser("rsrequester", REQUESTER_EMAIL, User.TYPE_USER);
        ensureUser("rssupport", SUPPORT_EMAIL, User.TYPE_SUPPORT);
    }

    @Transactional
    void seedResolutionMessages(Long ticketId, String bodyPrefix, LocalDateTime firstAt, LocalDateTime lastAt) {
        ensureResolutionUsers();
        Ticket ticket = Ticket.findById(ticketId);
        ensureTimedMessage(ticket, bodyPrefix + " opened", REQUESTER_EMAIL, firstAt);
        ensureTimedMessage(ticket, bodyPrefix + " closed", SUPPORT_EMAIL, lastAt);
        setTicketStatus(ticketId, "Closed");
    }

    @Transactional
    Long resolutionCompanyId(String companyName) {
        return ensureCompany(companyName);
    }

    void ensureResolutionAdmin() {
        ensureUser("resolutionadmin", "resolutionadmin@mnemosyne-systems.ai", User.TYPE_ADMIN);
    }

    List<Map<String, Object>> resolutionPoints(Long companyId) {
        ensureResolutionAdmin();
        return RestAssured.given().queryParam("companyId", companyId).get("/api/reports").then().statusCode(200)
                .extract().path("resolutionTime");
    }

    Double resolutionValue(List<Map<String, Object>> points, String category) {
        return resolutionAvg(points, category);
    }

    Double resolutionAvg(List<Map<String, Object>> points, String category) {
        Map<String, Object> point = resolutionPoint(points, category);
        return point == null ? null : ((Number) point.get("avg")).doubleValue();
    }

    Double resolutionMin(List<Map<String, Object>> points, String category) {
        Map<String, Object> point = resolutionPoint(points, category);
        return point == null ? null : ((Number) point.get("min")).doubleValue();
    }

    Double resolutionMax(List<Map<String, Object>> points, String category) {
        Map<String, Object> point = resolutionPoint(points, category);
        return point == null ? null : ((Number) point.get("max")).doubleValue();
    }

    Map<String, Object> resolutionPoint(List<Map<String, Object>> points, String category) {
        for (Map<String, Object> point : points) {
            if (category.equals(point.get("label"))) {
                return point;
            }
        }
        return null;
    }

    @Test
    @TestSecurity(user = "resolutionadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "resolutionadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "resolutionadmin") })
    void resolutionHappyPath() {
        Long companyId = resolutionCompanyId("Resolution Happy Co");
        Long ticketId = seedResolutionTicket("Resolution Happy Co", "Resolution Happy Cat");
        LocalDateTime firstAt = LocalDateTime.now().minusHours(5);
        seedResolutionMessages(ticketId, "Happy", firstAt, firstAt.plusHours(2));

        List<Map<String, Object>> points = resolutionPoints(companyId);

        Assertions.assertEquals(1, points.size());
        Assertions.assertEquals(2.0, resolutionValue(points, "Resolution Happy Cat"), 0.05);
        Assertions.assertEquals(2.0, resolutionMin(points, "Resolution Happy Cat"), 0.05);
        Assertions.assertEquals(2.0, resolutionMax(points, "Resolution Happy Cat"), 0.05);
    }

    @Test
    @TestSecurity(user = "resolutionadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "resolutionadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "resolutionadmin") })
    void resolutionReportsMinAvgMax() {
        Long companyId = resolutionCompanyId("Resolution Stats Co");
        LocalDateTime firstAt = LocalDateTime.now().minusHours(10);

        Long firstId = seedResolutionTicket("Resolution Stats Co", "Resolution Stats Cat");
        seedResolutionMessages(firstId, "Stats one", firstAt, firstAt.plusHours(2));

        Long secondId = seedResolutionTicket("Resolution Stats Co", "Resolution Stats Cat");
        seedResolutionMessages(secondId, "Stats two", firstAt, firstAt.plusHours(4));

        Long thirdId = seedResolutionTicket("Resolution Stats Co", "Resolution Stats Cat");
        seedResolutionMessages(thirdId, "Stats three", firstAt, firstAt.plusHours(6));

        List<Map<String, Object>> points = resolutionPoints(companyId);

        Assertions.assertEquals(1, points.size());
        Assertions.assertEquals(2.0, resolutionMin(points, "Resolution Stats Cat"), 0.05);
        Assertions.assertEquals(4.0, resolutionAvg(points, "Resolution Stats Cat"), 0.05);
        Assertions.assertEquals(6.0, resolutionMax(points, "Resolution Stats Cat"), 0.05);
    }

    @Test
    @TestSecurity(user = "resolutionadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "resolutionadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "resolutionadmin") })
    void resolutionExcludesNonClosedTicket() {
        Long companyId = resolutionCompanyId("Resolution Open Co");
        Long ticketId = seedResolutionTicket("Resolution Open Co", "Resolution Open Cat");
        LocalDateTime firstAt = LocalDateTime.now().minusHours(4);
        ensureResolutionUsers();
        Ticket ticket = Ticket.findById(ticketId);
        ensureTimedMessage(ticket, "Open request", REQUESTER_EMAIL, firstAt);
        ensureTimedMessage(ticket, "Open follow-up", SUPPORT_EMAIL, firstAt.plusHours(1));

        List<Map<String, Object>> points = resolutionPoints(companyId);

        Assertions.assertTrue(points.isEmpty(), "Non-closed ticket must be excluded entirely");
    }

    @Test
    @TestSecurity(user = "resolutionadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "resolutionadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "resolutionadmin") })
    void resolutionGroupsByCategory() {
        Long companyId = resolutionCompanyId("Resolution Group Co");
        LocalDateTime firstAt = LocalDateTime.now().minusHours(10);

        Long firstId = seedResolutionTicket("Resolution Group Co", "Resolution Group Cat A");
        seedResolutionMessages(firstId, "Group A", firstAt, firstAt.plusHours(2));

        Long secondId = seedResolutionTicket("Resolution Group Co", "Resolution Group Cat B");
        seedResolutionMessages(secondId, "Group B", firstAt, firstAt.plusHours(6));

        List<Map<String, Object>> points = resolutionPoints(companyId);

        Assertions.assertEquals(2, points.size());
        Assertions.assertEquals(2.0, resolutionValue(points, "Resolution Group Cat A"), 0.05);
        Assertions.assertEquals(6.0, resolutionValue(points, "Resolution Group Cat B"), 0.05);
    }

    @Test
    @TestSecurity(user = "resolutiontam", roles = "tam")
    @JwtSecurity(claims = { @Claim(key = "email", value = "resolutiontam@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "resolutiontam") })
    void resolutionRespectsTamCompanyScoping() {
        ensureUser("resolutiontam", "resolutiontam@mnemosyne-systems.ai", User.TYPE_TAM);
        Long ownCompanyId = resolutionCompanyId("Resolution Scope Co A");
        resolutionCompanyId("Resolution Scope Co B");
        ensureCompanyUsers(ownCompanyId, "resolutiontam@mnemosyne-systems.ai");
        LocalDateTime firstAt = LocalDateTime.now().minusHours(10);

        Long ownTicketId = seedResolutionTicket("Resolution Scope Co A", "Resolution Scope Cat A");
        seedResolutionMessages(ownTicketId, "Scope own", firstAt, firstAt.plusHours(2));

        Long otherTicketId = seedResolutionTicket("Resolution Scope Co B", "Resolution Scope Cat B");
        seedResolutionMessages(otherTicketId, "Scope other", firstAt, firstAt.plusHours(8));

        List<Map<String, Object>> points = RestAssured.given().get("/api/reports").then().statusCode(200)
                .body("role", Matchers.equalTo("tam")).extract().path("resolutionTime");

        Assertions.assertEquals(2.0, resolutionValue(points, "Resolution Scope Cat A"), 0.05);
        Assertions.assertNull(resolutionValue(points, "Resolution Scope Cat B"),
                "TAM must not see other companies reflected in the resolution average");
    }
}
