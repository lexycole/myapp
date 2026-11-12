package com.ubagroup.febaalert.scheduler;

import com.ubagroup.febaalert.dto.EmailAlertDto;
import com.ubagroup.febaalert.repository.EmailAlertRepository;
import com.ubagroup.febaalert.repository.EmailAlertRepository.RecentEmailAlertRow;
import com.ubagroup.febaalert.repository.EmailJobRepository;
import com.ubagroup.febaalert.repository.EmailJobRepository.EmailJob;
import com.ubagroup.febaalert.services.EmailAlertService;
import com.ubagroup.febaalert.exceptions.PermanentEmailException;
import com.ubagroup.febaalert.util.EmailAlertUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.Map;
import java.sql.Timestamp;


/**
 * Polls Oracle for recent alerts, queues them as jobs in Postgres, and sends the jobs that are due.
 * <p>
 * Each {@link #tick()} runs three steps in order: release stuck jobs, queue new alerts,
 * send due jobs. Oracle is read-only. All writes go to Postgres through {@code EmailJobRepository}.
 * </p>
 * <p>
 * Job lifecycle: a successful send becomes SENT, a {@link PermanentEmailException} becomes
 * FAILED immediately, and any other failure is retried after 1, 5 and 15 minutes, then FAILED.
 * Duplicates are blocked by a SHA-256 key stored in {@code source_alert_id}.
 * </p>
 *
 * @author Olawale Akinro
 * @version 2.0
 * @since 1.0
 */
@Component
public class EmailAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertScheduler.class);

    // 3 retries with growing delay (minutes)
    // Delay before retry 1, 2 and 3. The index is the number of retries already done.
    private static final int[] RETRY_DELAYS_MIN = {1, 5, 15};
    private static final int BATCH_SIZE = 50;
    private static final int STUCK_AFTER_MIN = 10;
    private static final long LOGIN_DUP_WINDOW_SEC = 5;

    private final EmailAlertService emailAlertService;
    private final EmailAlertRepository emailAlertRepository; // Oracle, READ-ONLY
    private final EmailJobRepository jobs;                   // Postgres, read/write
    private final int lookbackMin;
    private final Set<String> allowedAlertNames;

    /**
     * Creates the scheduler and parses the allowed alert names.
     * <p>
     *  {@code confirmAlertName} is split on commas, trimmed, and blank entries are dropped.
     * </p>
     *
     * @param emailAlertService    sends one alert as an HTML email
     * @param emailAlertRepository Oracle source of alerts (read-only)
     * @param jobs                 Postgres job queue (read/write)
     * @param lookbackMin          how many minutes back to read alerts ({@code EMAIL_JOB_LOOKBACK_MIN})
     * @param confirmAlertName     comma-separated alert names allowed to be emailed ({@code CONFIRM_ALERT_NAME})
     * @throws NullPointerException if {@code confirmAlertName} is null
     */
    public EmailAlertScheduler(
            EmailAlertService emailAlertService,
            EmailAlertRepository emailAlertRepository,
            EmailJobRepository jobs,
            @Value("${EMAIL_JOB_LOOKBACK_MIN}") int lookbackMin,
            @Value("${CONFIRM_ALERT_NAME}") String confirmAlertName) {
        log.info("Start Email Alert Scheduler construct >>>>>>>");
        this.emailAlertService = emailAlertService;
        this.emailAlertRepository = emailAlertRepository;
        this.jobs = jobs;
        this.lookbackMin = lookbackMin;
        this.allowedAlertNames = Arrays.stream(confirmAlertName.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Runs one polling cycle.
     * <p>
     *  Uses {@code fixedDelay} ({@code EMAIL_JOB_POLL_MS}), so the next run starts only after
     *  this one finishes (no overlap on one instance). Order matters: free stuck jobs,
     *  queue new alerts, then send what is due. A failure while queuing is logged and does not
     *  stop the sending step.
     * </p>
     *
     * @throws RuntimeException if {@code releaseStuck} or {@code claimDue} fails on the database;
     *                          it is not caught here, and Spring logs it and runs the next cycle
     */
    @Scheduled(fixedDelayString = "${EMAIL_JOB_POLL_MS}")
    public void tick() {
        log.info("Start Email Alert Scheduler tick >>>>>>>");
        int released = jobs.releaseStuck(STUCK_AFTER_MIN);
        if (released > 0) {
            log.info("Released {} stuck email jobs", released);
        }

        try {
            enqueueRecentAlerts();
        } catch (Exception ex) {
            log.error("Enqueue step failed; still processing due jobs", ex);
        }

        processDueJobs();
    }


    /**
     * Reads recent Oracle alerts and queues new email jobs in Postgres.
     * <p>
     *  Reads {@code lookbackMin + 1} minutes. The extra minute feeds the login duplicate check only.
     *  A row is skipped when its alert name is not in {@code CONFIRM_ALERT_NAME} or when
     *  its address has no {@code @} (phone-number rows). The duplicate key depends on the alert type:
     * </p>
     * <ul>
     *   <li>{@code LGIN_SUCC}: Oracle writes two rows per login, so only the first row per
     *       address within 5 seconds is kept. {@code CRE_TS} must be a date, and rows older than
     *       the lookback window only feed the duplicate check. Key prefix {@code login-v4}.</li>
     *   <li>{@code LGIN_FAIL}: unique per attempt via {@code ALERT_SEQ_NUM} (falls back to {@code CRE_TS}).
     *       Key prefix {@code fail-v1}.</li>
     *   <li>Other alerts: key includes sequence, address, name, subject and message. Key prefix {@code v3}.</li>
     * </ul>
     * <p>
     *  A job is created only if {@code jobs.enqueue} accepts the key (DB-level dedup).
     * </p>
     *
     * @throws IllegalStateException if the Oracle query or a Clob read fails; {@link #tick()} catches it
     */
    private void enqueueRecentAlerts() {
        log.info("Enqueue recent alerts from Oracle DB...");
        int queued = 0;

        // Last login time seen per user. A row within 5s of it is a duplicate.
        Map<String, Long> lastLogin = new HashMap<>();
        // One extra minute, so rows just outside the window still feed the duplicate check.
        for (RecentEmailAlertRow row : emailAlertRepository.findRecent(lookbackMin + 1)) {
            log.info("In the loop, this is the value of row: {}", row);

            // Columns are mapped to typed fields by the repository (ALERT_MESG is read as a String there).
            String name = EmailAlertUtil.textValue(row.alertName());
            // Only alert types listed in CONFIRM_ALERT_NAME get emailed.
            if (name == null || !allowedAlertNames.contains(name.trim())) {
                continue;
            }
            String rawAddress = EmailAlertUtil.textValue(row.address());
            String address = rawAddress == null ? null : rawAddress.trim();
            String subject = EmailAlertUtil.textValue(row.alertSubject());
            String message = EmailAlertUtil.textValue(row.alertMesg());

            log.info("this is the value of alert name: {}", name);

            if (address == null || !address.contains("@")) {
                continue; // skips CHANNEL4 phone-number rows without needing CHANNEL_ID in SQL
            }

            // Duplicate-prevention key.
            String n = name.trim();
            String key;
            // Oracle writes two rows per login, so keep only the first one per address within 5 seconds.
            if ("LGIN_SUCC".equals(n)) {
                log.info("Start success login alerts queue. this is the value of row.creTs(): {}", row.creTs());
                Timestamp creTs = row.creTs();
                if (creTs == null) {
                    log.error("LGIN_SUCC without usable CRE_TS ({}); skipped", creTs);
                    continue;
                }
                long ts = creTs.getTime() / 1000;
                String who = address.toLowerCase();
                Long anchor = lastLogin.get(who);
                if (anchor != null && ts - anchor <= LOGIN_DUP_WINDOW_SEC) {
                    continue; // second row of the same login
                }
                lastLogin.put(who, ts);
                // Older rows were fetched only to feed the duplicate check, so don't queue them.
                Long ageSec = row.ageSec();
                if (ageSec != null && ageSec.doubleValue() > lookbackMin * 60.0) {
                    continue; // margin row: only feeds the anchor map
                }
                key = sha256(String.join("|", "login-v4", String.valueOf(creTs), who, n));
            } else if ("LGIN_FAIL".equals(n)) {
                // Each failed attempt has its own ALERT_SEQ_NUM, so the key is unique per attempt.
                log.info(">>>>>>>>>>>>>> Start failed login alerts queue >>>>>>>>>>>");
                key = sha256(String.join("|", "fail-v1",
                        row.alertSeqNum() != null ? String.valueOf(row.alertSeqNum()) : String.valueOf(row.creTs()),
                        address.toLowerCase(),
                        n));
            } else {
                // Subject and message are in the key, so different content isn't treated as a duplicate.
                log.info(">>>>>>>>>> FOR OTHER ALERTS: Start other alerts queue >>>>>>>>>>>");
                // Fixed-length unique key. It is stored in source_alert_id for the DB-level dedup.
                key = sha256(String.join("|", "v3",
                        row.alertSeqNum() != null ? String.valueOf(row.alertSeqNum()) : String.valueOf(row.creTs()),
                        address.toLowerCase(),
                        n,
                        subject == null ? "" : subject.trim(),
                        message == null ? "" : message.trim()));
            }

            if (jobs.enqueue(key, name, address, subject, message)) {
                log.info("Queued email job for alert {} key {}", name, key);
                queued++;
            }
        }

        if (queued > 0) {
            log.info("Enqueued {} new email jobs", queued);
        }
    }

    /**
     * Sends the jobs that are due and records each outcome.
     * <p>
     * Claims up to 50 jobs. For each job:
     * </p>
     * <ul>
     *   <li>sent: marked SENT</li>
     *   <li>{@link PermanentEmailException}: marked FAILED, no retry</li>
     *   <li>service returned {@code false} or any other exception: retried after 1, 5 or 15 minutes
     *       based on the retry count, then marked FAILED once retries are used up</li>
     * </ul>
     * <p>
     * One failing job never stops the others, because every exception is handled per job.
     * </p>
     *
     * @throws RuntimeException if {@code claimDue} fails; per-job errors are handled inside the loop
     */
    private void processDueJobs() {
        log.info("Process due email jobs...");
        for (EmailJob job : jobs.claimDue(BATCH_SIZE)) {
            log.info(" ");
            try {
                log.info("Processing email job: job={}", job.id());
                EmailAlertDto alert = new EmailAlertDto(
                        job.address(),
                        job.subject(),
                        job.message(),
                        job.alertName()
                );

                if (!emailAlertService.sendLatestEmail(alert)) {
                    throw new IllegalStateException(
                            "Email was not sent for job " + job.id());
                }
                log.info("mark sent job");
                jobs.markSent(job.id());
            } catch (PermanentEmailException ex) {
                // Bad data (invalid address or alert name). Retrying won't help.
                jobs.markFailed(job.id(), err(ex));
            } catch (Exception ex) {
                log.error("Email job failed (attempt {}): job={}", job.retryCount(), job.id(), ex);
                int attempt = job.retryCount();
                if (attempt >= RETRY_DELAYS_MIN.length) {
                    // Retries used up, so mark it FAILED.
                    log.error("Email job failed permanently after {} attempts: job={}", attempt, job.id(), ex);
                    jobs.markFailed(job.id(), err(ex));
                } else {
                    log.info("Scheduling retry in {} minutes: job={}", RETRY_DELAYS_MIN[attempt], job.id());
                    jobs.markRetry(job.id(), attempt + 1, RETRY_DELAYS_MIN[attempt], err(ex));
                }
            }
        }
    }

    /**
     * Hashes a string into a fixed-length key for duplicate prevention.
     *
     * @param value the text to hash (parts joined with {@code |})
     * @return the SHA-256 hash as a 64-character lowercase hex string
     * @throws IllegalStateException if SHA-256 is unavailable on the JVM (not expected)
     */
    private static String sha256(String value) {
        log.info("this is the value of value: {}", value);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Shortens an exception to fit the job's error column.
     *
     * @param ex the exception to describe
     * @return {@code ex.toString()}, cut to the first 1000 characters
     */
    private static String err(Exception ex) {
        String s = ex.toString();
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}

