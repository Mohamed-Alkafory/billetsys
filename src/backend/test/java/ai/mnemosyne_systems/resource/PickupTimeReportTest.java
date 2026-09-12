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
import ai.mnemosyne_systems.model.event.Event;
import ai.mnemosyne_systems.model.event.EventConstants;
import ai.mnemosyne_systems.util.TicketTimeSupport;
import io.quarkus.hibernate.orm.panache.Panache;
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
class PickupTimeReportTest extends AccessTestSupport {

    @Transactional
    Long seedPickupTicket(String companyName, String categoryName) {
        Long companyId = ensureCompany(companyName);
        Category category = ensureCategory(categoryName, categoryName + " description", false);
        Ticket ticket = ensureTicket(companyId);
        Ticket managed = Ticket.findById(ticket.id);
        managed.category = category;
        return managed.id;
    }

    @Transactional
    void seedTicketEvent(Long ticketId, long eventType, LocalDateTime createdAt) {
        Ticket ticket = Ticket.findById(ticketId);
        Event event = new Event();
        event.key = ticket.id;
        event.eventType = eventType;
        event.companyId = ticket.company == null ? null : ticket.company.id;
        event.eventValue = eventType == EventConstants.TICKET_OPENED ? "Ticket opened" : "Ticket assigned";
        event.persist();
        Panache.getEntityManager().flush();
        Event.update("createdAt = ?1 where id = ?2", createdAt, event.id);
        Panache.getEntityManager().clear();
    }

    @Transactional
    Long pickupCompanyId(String companyName) {
        return ensureCompany(companyName);
    }

    void ensurePickupAdmin() {
        ensureUser("pickupadmin", "pickupadmin@mnemosyne-systems.ai", User.TYPE_ADMIN);
    }

    List<Map<String, Object>> pickupPoints(Long companyId) {
        ensurePickupAdmin();
        return RestAssured.given().queryParam("companyId", companyId).get("/api/reports").then().statusCode(200)
                .extract().path("pickupTime");
    }

    Double pickupValue(List<Map<String, Object>> points, String category) {
        return pickupAvg(points, category);
    }

    Double pickupAvg(List<Map<String, Object>> points, String category) {
        Map<String, Object> point = pickupPoint(points, category);
        return point == null ? null : ((Number) point.get("avg")).doubleValue();
    }

    Double pickupMin(List<Map<String, Object>> points, String category) {
        Map<String, Object> point = pickupPoint(points, category);
        return point == null ? null : ((Number) point.get("min")).doubleValue();
    }

    Double pickupMax(List<Map<String, Object>> points, String category) {
        Map<String, Object> point = pickupPoint(points, category);
        return point == null ? null : ((Number) point.get("max")).doubleValue();
    }

    Map<String, Object> pickupPoint(List<Map<String, Object>> points, String category) {
        for (Map<String, Object> point : points) {
            if (category.equals(point.get("label"))) {
                return point;
            }
        }
        return null;
    }

    @Test
    @TestSecurity(user = "pickupadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "pickupadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "pickupadmin") })
    void pickupTimeHappyPath() {
        Long companyId = pickupCompanyId("Pickup Happy Co");
        Long ticketId = seedPickupTicket("Pickup Happy Co", "Pickup Happy Cat");
        LocalDateTime opened = LocalDateTime.now().minusHours(5);
        seedTicketEvent(ticketId, EventConstants.TICKET_OPENED, opened);
        seedTicketEvent(ticketId, EventConstants.TICKET_ASSIGNED, opened.plusHours(2));

        List<Map<String, Object>> points = pickupPoints(companyId);

        Assertions.assertEquals(1, points.size());
        Assertions.assertEquals(2.0, pickupValue(points, "Pickup Happy Cat"), 0.05);
        Assertions.assertEquals(2.0, pickupMin(points, "Pickup Happy Cat"), 0.05);
        Assertions.assertEquals(2.0, pickupMax(points, "Pickup Happy Cat"), 0.05);
    }

    @Test
    @TestSecurity(user = "pickupadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "pickupadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "pickupadmin") })
    void pickupTimeUsesNowWhenNotAssigned() {
        Long companyId = pickupCompanyId("Pickup Unassigned Co");
        Long ticketId = seedPickupTicket("Pickup Unassigned Co", "Pickup Unassigned Cat");
        LocalDateTime opened = LocalDateTime.now().minusHours(4);
        seedTicketEvent(ticketId, EventConstants.TICKET_OPENED, opened);

        List<Map<String, Object>> points = pickupPoints(companyId);

        Double value = pickupValue(points, "Pickup Unassigned Cat");
        Assertions.assertNotNull(value, "Ticket without ASSIGNED event must not be excluded from the average");
        double expected = TicketTimeSupport.elapsedMinutes(opened, LocalDateTime.now()) / 60.0;
        Assertions.assertEquals(expected, value, 0.15);
    }

    @Test
    @TestSecurity(user = "pickupadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "pickupadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "pickupadmin") })
    void pickupTimeExcludesTicketWithoutOpenedEvent() {
        Long companyId = pickupCompanyId("Pickup No Opened Co");
        seedPickupTicket("Pickup No Opened Co", "Pickup No Opened Cat");

        List<Map<String, Object>> points = pickupPoints(companyId);

        Assertions.assertTrue(points.isEmpty(), "Ticket without OPENED event must be excluded entirely");
    }

    @Test
    @TestSecurity(user = "pickupadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "pickupadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "pickupadmin") })
    void pickupTimeIgnoresStaleAssignedBeforeOpened() {
        Long companyId = pickupCompanyId("Pickup Reopen Co");
        LocalDateTime opened = LocalDateTime.now().minusHours(5);

        Long fallbackId = seedPickupTicket("Pickup Reopen Co", "Pickup Reopen Fallback Cat");
        seedTicketEvent(fallbackId, EventConstants.TICKET_ASSIGNED, opened.minusHours(5));
        seedTicketEvent(fallbackId, EventConstants.TICKET_OPENED, opened);

        Long laterId = seedPickupTicket("Pickup Reopen Co", "Pickup Reopen Later Cat");
        seedTicketEvent(laterId, EventConstants.TICKET_ASSIGNED, opened.minusHours(5));
        seedTicketEvent(laterId, EventConstants.TICKET_OPENED, opened);
        seedTicketEvent(laterId, EventConstants.TICKET_ASSIGNED, opened.plusHours(1));

        List<Map<String, Object>> points = pickupPoints(companyId);

        Double fallback = pickupValue(points, "Pickup Reopen Fallback Cat");
        Assertions.assertNotNull(fallback);
        double expectedFallback = TicketTimeSupport.elapsedMinutes(opened, LocalDateTime.now()) / 60.0;
        Assertions.assertEquals(expectedFallback, fallback, 0.15);
        Assertions.assertEquals(1.0, pickupValue(points, "Pickup Reopen Later Cat"), 0.05);
    }

    @Test
    @TestSecurity(user = "pickupadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "pickupadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "pickupadmin") })
    void pickupTimeGroupsByCategory() {
        Long companyId = pickupCompanyId("Pickup Group Co");
        LocalDateTime opened = LocalDateTime.now().minusHours(10);

        Long firstId = seedPickupTicket("Pickup Group Co", "Pickup Group Cat A");
        seedTicketEvent(firstId, EventConstants.TICKET_OPENED, opened);
        seedTicketEvent(firstId, EventConstants.TICKET_ASSIGNED, opened.plusHours(2));

        Long secondId = seedPickupTicket("Pickup Group Co", "Pickup Group Cat B");
        seedTicketEvent(secondId, EventConstants.TICKET_OPENED, opened);
        seedTicketEvent(secondId, EventConstants.TICKET_ASSIGNED, opened.plusHours(6));

        List<Map<String, Object>> points = pickupPoints(companyId);

        Assertions.assertEquals(2, points.size());
        Assertions.assertEquals(2.0, pickupValue(points, "Pickup Group Cat A"), 0.05);
        Assertions.assertEquals(6.0, pickupValue(points, "Pickup Group Cat B"), 0.05);
    }

    @Test
    @TestSecurity(user = "pickupadmin@mnemosyne-systems.ai", roles = "admin")
    @JwtSecurity(claims = { @Claim(key = "email", value = "pickupadmin@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "pickupadmin") })
    void pickupTimeReportsMinAvgMax() {
        Long companyId = pickupCompanyId("Pickup Stats Co");
        LocalDateTime opened = LocalDateTime.now().minusHours(10);

        Long firstId = seedPickupTicket("Pickup Stats Co", "Pickup Stats Cat");
        seedTicketEvent(firstId, EventConstants.TICKET_OPENED, opened);
        seedTicketEvent(firstId, EventConstants.TICKET_ASSIGNED, opened.plusHours(2));

        Long secondId = seedPickupTicket("Pickup Stats Co", "Pickup Stats Cat");
        seedTicketEvent(secondId, EventConstants.TICKET_OPENED, opened);
        seedTicketEvent(secondId, EventConstants.TICKET_ASSIGNED, opened.plusHours(4));

        Long thirdId = seedPickupTicket("Pickup Stats Co", "Pickup Stats Cat");
        seedTicketEvent(thirdId, EventConstants.TICKET_OPENED, opened);
        seedTicketEvent(thirdId, EventConstants.TICKET_ASSIGNED, opened.plusHours(6));

        List<Map<String, Object>> points = pickupPoints(companyId);

        Assertions.assertEquals(1, points.size());
        Assertions.assertEquals(2.0, pickupMin(points, "Pickup Stats Cat"), 0.05);
        Assertions.assertEquals(4.0, pickupAvg(points, "Pickup Stats Cat"), 0.05);
        Assertions.assertEquals(6.0, pickupMax(points, "Pickup Stats Cat"), 0.05);
    }

    @Test
    @TestSecurity(user = "pickuptam", roles = "tam")
    @JwtSecurity(claims = { @Claim(key = "email", value = "pickuptam@mnemosyne-systems.ai"),
            @Claim(key = "sub", value = "pickuptam") })
    void pickupTimeRespectsTamCompanyScoping() {
        ensureUser("pickuptam", "pickuptam@mnemosyne-systems.ai", User.TYPE_TAM);
        Long ownCompanyId = pickupCompanyId("Pickup Scope Co A");
        pickupCompanyId("Pickup Scope Co B");
        ensureCompanyUsers(ownCompanyId, "pickuptam@mnemosyne-systems.ai");
        LocalDateTime opened = LocalDateTime.now().minusHours(10);

        Long ownTicketId = seedPickupTicket("Pickup Scope Co A", "Pickup Scope Cat A");
        seedTicketEvent(ownTicketId, EventConstants.TICKET_OPENED, opened);
        seedTicketEvent(ownTicketId, EventConstants.TICKET_ASSIGNED, opened.plusHours(2));

        Long otherTicketId = seedPickupTicket("Pickup Scope Co B", "Pickup Scope Cat B");
        seedTicketEvent(otherTicketId, EventConstants.TICKET_OPENED, opened);
        seedTicketEvent(otherTicketId, EventConstants.TICKET_ASSIGNED, opened.plusHours(8));

        List<Map<String, Object>> points = RestAssured.given().get("/api/reports").then().statusCode(200)
                .body("role", Matchers.equalTo("tam")).extract().path("pickupTime");

        Assertions.assertEquals(2.0, pickupValue(points, "Pickup Scope Cat A"), 0.05);
        Assertions.assertNull(pickupValue(points, "Pickup Scope Cat B"),
                "TAM must not see other companies reflected in the pickup average");
    }
}
