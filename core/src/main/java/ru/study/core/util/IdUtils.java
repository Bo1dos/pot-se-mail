package ru.study.core.util;

import java.util.UUID;

public final class IdUtils {
    private IdUtils() {}
    public static String randomId() { return UUID.randomUUID().toString(); }
}
