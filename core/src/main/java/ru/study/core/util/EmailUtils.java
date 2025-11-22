package ru.study.core.util;

import java.util.regex.Pattern;

public final class EmailUtils {
    private static final Pattern EMAIL = Pattern.compile(
        "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$",
        Pattern.CASE_INSENSITIVE
    );

    private EmailUtils() {}

    public static boolean isValid(String email) {
        return email != null && EMAIL.matcher(email).matches();
    }
}
