/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne_systems.service;

import ai.mnemosyne_systems.model.event.EventConstants;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Database-side aggregation for the three expensive per-ticket duration metrics (R1/R2 of #192).
 * <p>
 * Each query returns ONE SLIM ROW per ticket (ticket id, category name, raw timestamps only) — no Message body/author
 * hydration, no full Event entity hydration. The database does the filtering (company scope, event/message type) and
 * grouping (MIN/MAX/COUNT per ticket); all date arithmetic stays in Java so the SQL uses no Postgres-only functions
 * ({@code EXTRACT(EPOCH...)}, {@code NOW()}, {@code date_trunc}) and behaves identically on H2 (tests) and Postgres
 * (prod).
 * <p>
 * Business-logic guards preserved in the Java post-processing inside {@link ReportService}:
 * <ul>
 * <li>pickup: stale-ASSIGNED-before-OPENED guard (resolved here in SQL via {@code >= opened}), no-OPENED exclusion,
 * unassigned falls back to {@code now}, DST-aware via {@code TicketTimeSupport};</li>
 * <li>first response: no-support-reply exclusion, wall-clock via {@code Duration.between};</li>
 * <li>resolution: Closed-only filter (pre-filtered here in SQL plus a Java guard), wall-clock via
 * {@code Duration.between}.</li>
 * </ul>
 */
@ApplicationScoped
public class ReportQueryService {

    public record PickupRow(Long ticketId, String categoryName, LocalDateTime openedAt, LocalDateTime assignedAt) {
    }

    public record FirstResponseRow(Long ticketId, String categoryName, LocalDateTime firstAt, LocalDateTime replyAt,
            long messageCount) {
    }

    public record ResolutionRow(Long ticketId, String categoryName, String status, LocalDateTime firstAt,
            LocalDateTime lastAt) {
    }

    /**
     * One slim row per ticket: earliest OPENED event plus earliest ASSIGNED event at or after that OPENED. The
     * {@code >= opened} predicate resolves the stale-ASSIGNED-before-OPENED case inside the database; tickets with no
     * OPENED row yield {@code openedAt == null} and are excluded by the caller.
     */
    public List<PickupRow> pickupRows(List<Long> companyIds) {
        String companyFilter = companyFilterSql(companyIds);
        String sql = "select t.id, c.name, "
                + "(select min(e1.created_at) from events e1 where e1.event_key = t.id and e1.event_type = "
                + EventConstants.TICKET_OPENED + "), "
                + "(select min(e2.created_at) from events e2 where e2.event_key = t.id and e2.event_type = "
                + EventConstants.TICKET_ASSIGNED
                + " and e2.created_at >= (select min(e3.created_at) from events e3 where e3.event_key = t.id and e3.event_type = "
                + EventConstants.TICKET_OPENED + ")) " + "from tickets t left join categories c on c.id = t.category_id"
                + companyFilter;
        List<Object[]> rows = Panache.getEntityManager().createNativeQuery(sql).getResultList();
        List<PickupRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new PickupRow(toLong(row[0]), (String) row[1], toDateTime(row[2]), toDateTime(row[3])));
        }
        return result;
    }

    /**
     * One slim row per ticket: earliest message date, earliest support/admin reply to a *different* message, and the
     * message count. The reply excludes the first message by row identity (lowest id among the earliest-date messages),
     * not by date comparison — so two distinct messages sharing the exact same timestamp still count as a 0h reply,
     * while a lone support-authored first message can never count as replying to itself. Tickets with fewer than two
     * messages or no later support reply yield {@code replyAt == null} and are excluded by the caller.
     */
    public List<FirstResponseRow> firstResponseRows(List<Long> companyIds) {
        String companyFilter = companyFilterSql(companyIds);
        String sql = "select t.id, c.name, " + "(select min(m1.date) from messages m1 where m1.ticket_id = t.id), "
                + "(select min(m2.date) from messages m2 join users u on u.id = m2.author_id "
                + "where m2.ticket_id = t.id and lower(u.user_type) in ('support', 'admin') "
                + "and m2.id <> (select min(m0.id) from messages m0 where m0.ticket_id = t.id "
                + "and m0.date = (select min(m5.date) from messages m5 where m5.ticket_id = t.id))), "
                + "(select count(*) from messages m4 where m4.ticket_id = t.id) "
                + "from tickets t left join categories c on c.id = t.category_id" + companyFilter;
        List<Object[]> rows = Panache.getEntityManager().createNativeQuery(sql).getResultList();
        List<FirstResponseRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new FirstResponseRow(toLong(row[0]), (String) row[1], toDateTime(row[2]), toDateTime(row[3]),
                    toLong(row[4])));
        }
        return result;
    }

    /**
     * One slim row per Closed ticket: earliest and latest message dates. Closed-only is pre-filtered in SQL (so the
     * database does the work) and re-checked in Java by the caller.
     */
    public List<ResolutionRow> resolutionRows(List<Long> companyIds) {
        String companyFilter = companyFilterSql(companyIds);
        String statusFilter = "lower(t.status) = 'closed'";
        String where = companyFilter.isEmpty() ? " where " + statusFilter : companyFilter + " and " + statusFilter;
        String sql = "select t.id, c.name, t.status, "
                + "(select min(m1.date) from messages m1 where m1.ticket_id = t.id), "
                + "(select max(m2.date) from messages m2 where m2.ticket_id = t.id) "
                + "from tickets t left join categories c on c.id = t.category_id" + where;
        List<Object[]> rows = Panache.getEntityManager().createNativeQuery(sql).getResultList();
        List<ResolutionRow> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new ResolutionRow(toLong(row[0]), (String) row[1], (String) row[2], toDateTime(row[3]),
                    toDateTime(row[4])));
        }
        return result;
    }

    /**
     * Company scoping embedded as validated long literals (ids come from managed entities, never raw user input, so no
     * injection surface). Null or empty means "all companies" — mirroring {@link ReportService#buildReportData}, where
     * an empty filter list also loads every ticket.
     */
    private String companyFilterSql(List<Long> companyIds) {
        if (companyIds == null || companyIds.isEmpty()) {
            return "";
        }
        StringBuilder ids = new StringBuilder();
        for (Long id : companyIds) {
            if (id == null) {
                continue;
            }
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id.longValue());
        }
        if (ids.length() == 0) {
            return "";
        }
        return " where t.company_id in (" + ids + ")";
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private static LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString());
    }
}
