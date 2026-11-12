package com.ubagroup.febaalert.services;

import com.ubagroup.febaalert.dto.EmailAlertDto;
import com.ubagroup.febaalert.exceptions.PermanentEmailException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import static com.ubagroup.febaalert.util.EmailAlertUtil.textValue;


/**
 * Validates one email alert and sends it as an HTML email over SMTP.
 * <p>
 *  Called by {@code EmailAlertScheduler.processDueJobs} for each due job. It re-checks the
 *  alert name against {@code CONFIRM_ALERT_NAME} and validates the recipient address.
 *  It then renders the Thymeleaf template {@code email/alert} with the inline banner image
 *  and hands the message to {@link org.springframework.mail.javamail.JavaMailSender}.
 * </p>
 * <p>
 *  Failures are split in two so the scheduler can react correctly:
 *  bad data throws {@link PermanentEmailException} (job goes to FAILED, no retry),
 *  and temporary errors such as SMTP downtime return {@code false} (job is retried).
 * </p>
 *
 * @author Olawale Akinro
 * @version 2.0
 * @since 1.0
 */
@Service
public class EmailAlertService {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertService.class);

    private final SpringTemplateEngine templateEngine;
    private final JavaMailSender mailSender;
    private final String fromAddress;
    // Quick format check before the stricter InternetAddress validation.
    private static final String EMAIL_REGEXP =
            "(?i)^[a-z0-9]+([_.-][a-z0-9]+)*@[a-z0-9]+([.-][a-z0-9]+)*\\.[a-z]{2,}$";

    // Comma-separated list of alert names allowed to be sent.
    @Value("${CONFIRM_ALERT_NAME}")
    private String confirmAlertName;

    public EmailAlertService(
            SpringTemplateEngine templateEngine,
            JavaMailSender mailSender,
            @Value("${UBA_ALERT_FROM_ADDRESS}") String fromAddress) {

        this.templateEngine = templateEngine;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /**
     * Validates and sends one alert email.
     * <p>
     * Steps: confirm the alert name is allowed, validate the recipient address
     * (regex, then strict {@link jakarta.mail.internet.InternetAddress} parsing),
     * build the greeting name from the address prefix, render the template,
     * then send. The elapsed time is logged on success and on failure.
     * </p>
     *
     * @param alert the alert to send (address, subject, message and alert name)
     * @return {@code true} if the SMTP server accepted the email, or {@code false} on a
     *         temporary failure (SMTP down, template or mail error) so the caller can retry
     * @throws PermanentEmailException if the alert name is not listed in {@code CONFIRM_ALERT_NAME},
     *                                 or the address is missing or invalid. Retrying cannot fix these.
     * @throws NullPointerException    if {@code alert} is null
     */
    public boolean sendLatestEmail(EmailAlertDto alert) {
        long start = System.nanoTime();

        log.info("Start Sending Latest Email...");
        try {
            log.info("Start Sending Latest Email in try block...");

            String rawName = textValue(alert.alertName());
            String alertName = rawName == null ? null : rawName.trim();

            // Re-checked here in case the config changed after the job was queued.
            boolean allowed = alertName != null
                    && java.util.Arrays.stream(confirmAlertName.split(","))
                    .map(String::trim)
                    .anyMatch(value -> value.equals(alertName));

            if (!allowed) {
                throw new PermanentEmailException("unsupported alert name: " + alertName);
            }

//            String toAddress = alert.address() == null ? "" : textValue(alert.address()).trim();
            String toAddress = "wal2.walew@ubagroup.com";

            if (toAddress.isBlank()) {
                throw new PermanentEmailException("address is missing");
            }

            if (!toAddress.matches(EMAIL_REGEXP)) {
                log.error("Invalid email address: {}", toAddress);
                throw new PermanentEmailException("invalid address: " + toAddress);
            }

            // true = strict parsing. validate() throws if the address is invalid.
            InternetAddress to = new InternetAddress(toAddress, true);
            to.validate();

            String subject = textValue(alert.alertSubject());
            String message = textValue(alert.alertMesg());

            // Builds a greeting name from the email prefix (john.doe becomes john doe).
            String name = toAddress.substring(0, toAddress.indexOf('@'))
                    .replace('.', ' ')
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .trim();

            // Thymeleaf variables. The template reads them as ${subject} and so on.
            Context context = new Context();
            context.setVariable("subject", subject);
            context.setVariable("message", message);
            context.setVariable("recipientName", name);
            context.setVariable("alertName", alertName);

            // Renders email/alert.html into an HTML string.
            String html = templateEngine.process("email/alert", context);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            // true = multipart, which is required for the inline image.
            MimeMessageHelper helper =
                    new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setCc(new String[] {"wal3.walew@ubagroup.com", "wal7.walew@ubagroup.com", textValue(alert.address()).trim()});
            helper.setSubject(subject);
            helper.setText(html, true);

            // Embeds the banner in the email. The template refers to it as cid:udirectalertBannerTopFw.
            helper.addInline(
                    "udirectalertBannerTopFw",
                    new ClassPathResource(
                            "templates/email/images/udirectalert_banner_top.fw.png"
                    ),
                    "image/png"
            );

            mailSender.send(mimeMessage);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info(">>>>>>>>>>>>>>>>>>>>>>> Email accepted by SMTP server: durationMs={}", elapsedMs);
            log.info(">>>>>>>>>>>>>>>>>>>>>>> Email notification sent successfully >>>>>>>>>>>>");
            return true;
        } catch (PermanentEmailException ex) {
            // Rethrow so the scheduler marks it FAILED without retrying.
            throw ex;
        } catch (Exception ex) {
            // Temporary failure (SMTP down, etc.). Returning false makes the scheduler retry.
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error(
                    "\n\n\n************* Email alert processing failed after {} ms \n\n\n ********",
                    elapsedMs,
                    ex
            );
            return false;
        }
    }
}
