package com.ubagroup.febaalert.util;

/**
 * Null-safe text helper for the email alert flow.
 * <p>
 *  Alert columns are mapped to {@code String} fields by the repository.
 *  This class turns a null value into {@code ""}.
 * </p>
 *
 * @author Olawale
 * @version 2.0
 * @since 1.0
 */
public final class EmailAlertUtil {

    /**
     * Null-safe String normalizer.
     *
     * @param value the text, may be null
     * @return {@code value}, or {@code ""} if it is null
     */
    public static String textValue(String value) {
        return value == null ? "" : value;
    }
}

