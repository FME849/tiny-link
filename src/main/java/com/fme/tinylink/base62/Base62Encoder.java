package com.fme.tinylink.base62;

public class Base62Encoder {
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public static String encode(long num) {
        if (num == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }

        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            int remainder = (int) (num % 62);
            sb.append(ALPHABET.charAt(remainder));
            num /= 62;
        }

        // Reverse because remainders are calculated from right to left
        return sb.reverse().toString();
    }
}
