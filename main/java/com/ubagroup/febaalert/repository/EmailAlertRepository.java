package com.ubagroup.febaalert.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import java.sql.Timestamp;

/**
 * <p>
 *      Alerts from the last N minutes, oldest first so they are queued in order.
 *      Oracle date math is in days, so * 86400 gives seconds.
 *      2-minute time bucket. It has no alias and the scheduler doesn't read it, so it can go.
 *      1440 minutes in a day, so this converts minutes to days.
 *      Email channel only. Phone/SMS rows are on another channel.
 *      Named parameter binding, so there is no SQL injection and no string concatenation.
 *      catch (DataAccessException ex) → // Log the timing, then rethrow so the caller still sees the failure.
 * </p>
 *
 * @author Olawale Akinro
 * @version 2.0
 * @since 1.0
 */
@Repository
public class EmailAlertRepository {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertRepository.class);
    private final JdbcClient jdbcClient;
    private final String recentSql;

    public EmailAlertRepository(JdbcClient jdbcClient,
                                @Value("${ALERT_RECENT_QUERY}") String recentSql) {
        this.jdbcClient = jdbcClient;
        this.recentSql = recentSql;
    }

    /**
     * @param address
     * @param alertSubject
     * @param alertMesg
     * @param alertName
     * @param bankId
     * @param orgId
     * @param userId
     * @param alertSeqNum
     * @param creTs
     * @param ageSec
     * @param timeBucket
     */
    public record RecentEmailAlertRow(
            String address,
            String alertSubject,
            String alertMesg,
            String alertName,
            String bankId,
            String orgId,
            String userId,
            Long alertSeqNum,
            Timestamp creTs,
            Long ageSec,
            Long timeBucket
    ) {}


    /**
     *  @param lookbackMin how many minutes back to fetch alerts, counted from now (e.g. 5 = alerts created in the last 5 minutes)
     *  @return rows from {@code ececuser.aldt} for the email channel, oldest first (ordered by {@code R_CRE_TIME}, then {@code ALERT_SEQ_NUM});
     *  each map is keyed by column name (ADDRESS, ALERT_SUBJECT, ALERT_MESG, ALERT_NAME, BANK_ID, ORG_ID, USER_ID, ALERT_SEQ_NUM, CRE_TS, AGE_SEC);
     *  empty list if none match
     *  @throws DataAccessException if the query fails (connection, SQL, or timeout); logged with elapsed time, then rethrown
     */
    public List<RecentEmailAlertRow> findRecent(int lookbackMin) {
        long start = System.nanoTime();
        log.info("Fetching recent email alerts: lookbackMin={}", lookbackMin);
        try {
            List<RecentEmailAlertRow> rows = jdbcClient.sql(recentSql)
                    .param("lookbackMin", lookbackMin)
                    .query(RecentEmailAlertRow.class)
                    .list();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("Recent email alerts query completed: rows={}, durationMs={}", rows.size(), elapsedMs);
            return rows;
        } catch (DataAccessException ex) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("Recent email alerts query failed after {} ms", elapsedMs, ex);
            throw ex;
        }
    }
}
