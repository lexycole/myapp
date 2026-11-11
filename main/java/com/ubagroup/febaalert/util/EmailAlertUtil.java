package com.ubagroup.febaalert.util;

import com.ubagroup.febaalert.dto.EmailAlertDto;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.Map;

// NOTE: PATTERN MATCHING & WHY USE OBJECT, INSTANCE.
/**
 * Converts raw Oracle result rows into text for the email alert flow.
 * <p>
 * Oracle columns arrive as {@code Object} because JDBC returns {@code Map<String, Object>}.
 * This class converts them to {@code String} at that boundary. Null becomes {@code ""},
 * and {@link java.sql.Clob} values are read explicitly because {@code toString()}
 * does not return their text.
 * </p>
 *
 * @author Olawale
 * @version 1.0
 */
public final class EmailAlertUtil {

    /**
     * Maps one Oracle alert row to an {@link EmailAlertDto}.
     * <p>
     * Reads ADDRESS, ALERT_SUBJECT, ALERT_MESG and ALERT_NAME. Call this while the
     * DB row is still available, so a Clob such as ALERT_MESG can be read.
     * </p>
     *
     * @param row one row from the Oracle query, keyed by column name
     * @return a DTO whose fields are never null ({@code ""} for missing values)
     * @throws NullPointerException  if {@code row} is null
     * @throws IllegalStateException if a Clob value cannot be read or is too large
     */
    public static EmailAlertDto toDto(Map<String, Object> row) {
        return new EmailAlertDto(
                columnText(row.get("ADDRESS")),
                columnText(row.get("ALERT_SUBJECT")),
                columnText(row.get("ALERT_MESG")),
                columnText(row.get("ALERT_NAME"))
        );
    }

    /**
     * Converts one raw column value to a String.
     * <p>
     * The only method that accepts {@code Object}, because the JDBC map forces it.
     * Expected runtime types: {@code null}, {@code String}, {@link java.sql.Clob},
     * or any other type, which goes through {@code toString()}.
     * </p>
     *
     * @param value the raw column value, may be null
     * @return the text value, or {@code ""} if {@code value} is null
     * @throws IllegalStateException if {@code value} is a Clob that cannot be read
     */
    private static String columnText(Object value) {
        if (value instanceof Clob clob) {
                return textValue(clob);
            }
            return textValue(value == null ? null : value.toString());
        }

    /**
     * Null-safe String normalizer.
     *
     * @param value the text, may be null
     * @return {@code value}, or {@code ""} if it is null
     */
    public static String textValue(String value) {
            return value == null ? "" : value;
        }

    /**
     * Reads the full text of an Oracle Clob.
     * <p>
     * Clob positions start at 1, not 0, so the read starts at position 1.
     * </p>
     *
     * @param clob the Clob to read, may be null
     * @return the Clob text, or {@code ""} if {@code clob} is null or empty
     * @throws IllegalStateException if the Clob is longer than {@link Integer#MAX_VALUE}
     *                               characters, or if reading it throws {@link java.sql.SQLException}
     */
    public static String textValue(Clob clob) {
       if (clob == null) {
              return "";
       }
       try {
            long length = clob.length();

            if (length == 0) {
                return "";
            }

            if (length > Integer.MAX_VALUE) {
                throw new IllegalStateException("Email text exceeds supported length");
            }

            return clob.getSubString(1, (int) length);

       } catch (SQLException ex) {
           throw new IllegalStateException("Failed to read email text from database", ex);
       }
   }
}
