package com.logss.main.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reduces SQL to its shape so the same query with different values groups together. */
public final class SqlShapes {
    // Unrolled form of '(?:[^']|'')*': an alternation inside a loop recurses once per character in
    // java.util.regex and overflows the stack on long literals (pgjdbc sends a 4.5 KB one at startup).
    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*(?:''[^']*)*'");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern IN_LIST = Pattern.compile("\\bin\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SqlShapes() {
    }

    /** Lowercase, literals replaced by ?, IN (?, ?, ?) collapsed to IN (?), whitespace collapsed. */
    public static String normalize(String sql) {
        String shape = sql.toLowerCase(Locale.ROOT);
        shape = STRING_LITERAL.matcher(shape).replaceAll("?");
        shape = NUMBER_LITERAL.matcher(shape).replaceAll("?");
        shape = IN_LIST.matcher(shape).replaceAll("in (?)");
        return WHITESPACE.matcher(shape).replaceAll(" ").trim();
    }

    public static String hash(String normalizedSql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalizedSql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
