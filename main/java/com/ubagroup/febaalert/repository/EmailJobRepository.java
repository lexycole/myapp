package com.ubagroup.febaalert.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Postgres job queue. Lifecycle: enqueue, claimDue, then markSent, markRetry or markFailed.
 *
 * @author Olawale Akinro
 * @version 2.0
 */

@Repository
public class EmailJobRepository {

    /**
     * One queued email job.
     *
     * @param id            primary key of the job row
     * @param sourceAlertId unique key of the source alert (dedup key)
     * @param alertName     alert type name
     * @param address       recipient email address
     * @param subject       email subject
     * @param message       email body
     * @param retryCount    number of retries already attempted
     */
    public record EmailJob(
            long id,
            String sourceAlertId,
            String alertName,
            String address,
            String subject,
            String message,
            int retryCount) {
    }

    // Maps one DB row (snake_case columns) to an EmailJob.
    private static final RowMapper<EmailJob> MAPPER = (rs, i) -> new EmailJob(
            rs.getLong("id"),
            rs.getString("source_alert_id"),
            rs.getString("alert_name"),
            rs.getString("address"),
            rs.getString("subject"),
            rs.getString("message"),
            rs.getInt("retry_count"));

    private final JdbcTemplate jdbc;

    @Value("${EMAIL_JOB_ENQUEUE_SQL}")
    private String enqueueSql;

    @Value("${EMAIL_JOB_CLAIM_SQL}")
    private String claimSql;

    @Value("${EMAIL_JOB_MARK_SENT_SQL}")
    private String markSentSql;

    @Value("${EMAIL_JOB_MARK_RETRY_SQL}")
    private String markRetrySql;

    @Value("${EMAIL_JOB_MARK_FAILED_SQL}")
    private String markFailedSql;

    @Value("${EMAIL_JOB_RELEASE_STUCK_SQL}")
    private String releaseStuckSql;

    private static final Logger log = LoggerFactory.getLogger(EmailJobRepository.class);

    /**
     * Creates the jobs DB pool and runs {@code schema.sql}.
     *
     * @param url      JDBC URL of the Postgres jobs DB ({@code JOBS_DB_URL})
     * @param user     DB username ({@code JOBS_DB_USER})
     * @param password DB password ({@code JOBS_DB_PASSWORD})
     * @throws org.springframework.dao.DataAccessException if the DB is unreachable
     *         or {@code schema.sql} fails (e.g. {@code ScriptException}); aborts app startup
     */
    public EmailJobRepository(
            @Value("${JOBS_DB_URL}") String url,
            @Value("${JOBS_DB_USER}") String user,
            @Value("${JOBS_DB_PASSWORD}") String password) {

        log.info("Email Job Repository using jobs DB via postgres: {}", url);
        // Own connection pool, separate from Oracle. 3 connections is enough for one poller.
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(3);

        // Runs schema.sql on startup. It must be idempotent (CREATE TABLE IF NOT EXISTS).
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        this.jdbc = new JdbcTemplate(ds);
    }

    /** Inserts a PENDING job; returns false if this source alert is already queued.
     * source_alert_id is UNIQUE, so a repeat key is silently skipped. This is the dedup.
     * > 0 (in enqueue) → // update() returns the rows inserted. 0 means the alert was already queued.
     *
     * @param sourceAlertId unique dedup key of the source alert
     * @param alertName     alert type name
     * @param address       recipient email address
     * @param subject       email subject
     * @param message       email body
     * @return {@code true} if a new job was inserted, {@code false} if already queued
     * @throws org.springframework.dao.DataAccessException on any DB error
     */
    public boolean enqueue(String sourceAlertId, String alertName, String address,
                           String subject, String message) {
        boolean inserted = jdbc.update(enqueueSql, sourceAlertId, alertName, address, subject, message) > 0;
        log.info("enqueue sourceAlertId={} alertName={} inserted={}", sourceAlertId, alertName, inserted);
        return inserted;
    }

    /** Claims due jobs in one atomic statement (auto-commits before any email is sent).
     * Marks jobs PROCESSING and returns them in one statement, so nobody else can claim them.
     * Skips rows locked by another instance, so two app instances never take the same job.
     * Only jobs whose retry time has arrived.
     *
     * @param limit maximum number of jobs to claim
     * @return the claimed jobs, oldest id first; empty if none are due
     * @throws org.springframework.dao.DataAccessException on any DB error
     */
    public List<EmailJob> claimDue(int limit) {
        List<EmailJob> jobs = jdbc.query(claimSql, MAPPER, limit);
        log.info("claimDue limit={} claimed={}", limit, jobs.size());
        return jobs;
    }

    /**
     * Marks a job SENT and clears its last error.
     *
     * @param id job id
     * @throws org.springframework.dao.DataAccessException on any DB error
     */
    public void markSent(long id) {
        jdbc.update(markSentSql, id);
        log.info("markSent id={}", id);
    }

    /** Next attempt is after 1 min, then 5, then 15, then FAILED from now.
     *
     * @param id           job id
     * @param retryCount   new retry count to store
     * @param delayMinutes minutes to wait before the next attempt
     * @param error        error message from the failed attempt
     * @throws org.springframework.dao.DataAccessException on any DB error
     */
    public void markRetry(long id, int retryCount, int delayMinutes, String error) {
        jdbc.update(markRetrySql, retryCount, error, delayMinutes, id);
        log.info("markRetry id={} retryCount={} delayMinutes={} error={}", id, retryCount, delayMinutes, error);
    }

    /**
     * Marks a job FAILED (no more retries).
     *
     * @param id    job id
     * @param error final error message
     * @throws org.springframework.dao.DataAccessException on any DB error
     */
    public void markFailed(long id, String error) {
        jdbc.update(markFailedSql, error, id);
        log.error("markFailed id={} error={}", id, error);
    }

    /** Crash recovery: jobs stuck in PROCESSING go back to RETRY.
     *
     * @param olderThanMinutes only jobs untouched for longer than this are released
     * @return number of jobs released
     * @throws org.springframework.dao.DataAccessException on any DB error
     */
    public int releaseStuck(int olderThanMinutes) {
        int released = jdbc.update(releaseStuckSql, olderThanMinutes);
        if (released > 0) log.info("releaseStuck released={} olderThanMinutes={}", released, olderThanMinutes);
        return released;
    }
}

