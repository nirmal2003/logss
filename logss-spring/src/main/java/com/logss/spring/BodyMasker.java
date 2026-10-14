package com.logss.spring;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Replaces the values of sensitive fields in JSON and form-encoded bodies with "***". */
public class BodyMasker {
    static final String MASK = "***";

    private final Pattern json;
    private final Pattern form;

    public BodyMasker(List<String> fields) {
        String names = fields.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        // "password": "value"  or  "pin": 1234  (strings with escaped quotes, numbers, booleans, null)
        this.json = Pattern.compile("(\"(?:" + names + ")\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d[\\d.eE+-]*|true|false|null)",
                Pattern.CASE_INSENSITIVE);
        // password=value&...
        this.form = Pattern.compile("((?:^|&)(?:" + names + ")=)[^&]*", Pattern.CASE_INSENSITIVE);
    }

    public String mask(String body) {
        String masked = json.matcher(body).replaceAll("$1\"" + MASK + "\"");
        return form.matcher(masked).replaceAll("$1" + MASK);
    }
}
