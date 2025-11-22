package ru.study.core.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class DateTimeUtils {
    private static final DateTimeFormatter DEFAULT_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                         .withZone(ZoneId.systemDefault());

    private DateTimeUtils() {}

    public static String format(Instant instant) {
        return instant == null ? "" : DEFAULT_FORMATTER.format(instant);
    }
}
