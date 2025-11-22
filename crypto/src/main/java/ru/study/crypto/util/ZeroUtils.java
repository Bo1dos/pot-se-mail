package ru.study.crypto.util;

import java.util.Arrays;

public final class ZeroUtils {
    private ZeroUtils() {}

    public static void wipe(byte[] array) {
        if (array == null) return;
        Arrays.fill(array, (byte) 0);
    }

    public static void wipe(char[] array) {
        if (array == null) return;
        Arrays.fill(array, '\u0000');
    }
}
