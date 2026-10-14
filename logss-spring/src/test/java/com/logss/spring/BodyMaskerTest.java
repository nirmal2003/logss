package com.logss.spring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BodyMaskerTest {
    private final BodyMasker masker = new BodyMasker(new LogssCaptureProperties().getMaskedFields());

    @Test
    void masksJsonValuesOfAnyType() {
        assertEquals("{\"email\":\"a@b.c\",\"Password\": \"***\",\"pin\":1,\"cvv\":\"***\",\"token\":\"***\"}",
                masker.mask("{\"email\":\"a@b.c\",\"Password\": \"p\\\"w\",\"pin\":1,\"cvv\":123,\"token\":null}"));
    }

    @Test
    void masksFormParameters() {
        assertEquals("user=nirmal&password=***&next=/home", masker.mask("user=nirmal&password=hunter2&next=/home"));
    }

    @Test
    void leavesFieldsThatOnlyContainAMaskedName() {
        BodyMasker strict = new BodyMasker(List.of("token"));
        assertEquals("{\"tokenCount\":3}", strict.mask("{\"tokenCount\":3}"));
    }
}
