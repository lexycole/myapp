package com.ubagroup.febaalert.exceptions;

/**
 * Signals a non-retryable email failure.
 * <p>
 *  Thrown by {@code EmailAlertService.sendLatestEmail} when the job data is bad
 *  (invalid or missing address, unsupported alert name). Retrying cannot fix this,
 *  so {@code EmailAlertScheduler.processDueJobs} catches it and marks the job
 *  FAILED immediately, skipping the retry schedule.
 * </p>
 * <p>.
 *  Temporary failures (SMTP down, etc.) must not use this class. They return
 *  {@code false} from the service and get a delayed retry.
 * </p>
 *
 * @author Olawale Akinro
 * @version 2.0
 * @since 1.0
 */
public class PermanentEmailException extends RuntimeException {

    /**
     * Creates the exception with the reason the email can never be sent.
     *
     * @param message the failure reason, stored on the job as its error text
     */
    public PermanentEmailException(String message) {
        super(message);
    }
}

