package com.logss.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Settings under {@code logss.capture}. Off by default: bodies can contain personal data, so the app
 * opts in, e.g. with {@code LOGSS_CAPTURE_ENABLED=true} when running locally.
 */
@ConfigurationProperties("logss.capture")
public class LogssCaptureProperties {

    /** Whether request and response bodies are added to the current server span. */
    private boolean enabled = false;

    /** Bodies longer than this are cut off. */
    private int maxBodyBytes = 16 * 1024;

    /** JSON fields and form parameters whose values are replaced with "***". Matched case-insensitively. */
    private List<String> maskedFields = List.of(
            "password", "passwd", "pwd", "secret", "client_secret", "token", "access_token", "refresh_token",
            "id_token", "authorization", "api_key", "apikey", "card_number", "cardNumber", "cvv", "cvc", "ssn");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public List<String> getMaskedFields() {
        return maskedFields;
    }

    public void setMaskedFields(List<String> maskedFields) {
        this.maskedFields = maskedFields;
    }
}
