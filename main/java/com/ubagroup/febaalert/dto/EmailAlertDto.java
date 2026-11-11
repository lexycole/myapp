package com.ubagroup.febaalert.dto;

/**
 *
 * <p>
 * Carries one alert's email data between layers (Oracle row -> queue -> mail sender).
 * Plain Strings: callers get ready-to-use text, no casting or Clob handling.
 * </p>
 *
 * @param address
 * @param alertSubject
 * @param alertMesg
 * @param alertName
 *
 * @author olawale akinto
 * @version 2.0
 */

public record EmailAlertDto(
        String address,
        String alertSubject,
        String alertMesg,
        String alertName
) {}


