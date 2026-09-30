package com.example.storage.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StreamLimiterTest {

    @Test
    void esgotaNoLimiteELiberaAoFechar() {
        StreamLimiter limiter = new StreamLimiter(2);

        StreamLimiter.Permit first = limiter.tryAcquire().orElseThrow();
        limiter.tryAcquire().orElseThrow();
        assertTrue(limiter.tryAcquire().isEmpty(), "sem vaga, não bloqueia");

        first.close();
        assertTrue(limiter.tryAcquire().isPresent());
    }

    @Test
    void fecharDuasVezesSoltaUmaVagaSo() {
        StreamLimiter limiter = new StreamLimiter(1);
        StreamLimiter.Permit permit = limiter.tryAcquire().orElseThrow();

        permit.close();
        permit.close();

        assertEquals(1, limiter.available());
    }

    @Test
    void validaOLimite() {
        assertThrows(IllegalArgumentException.class, () -> new StreamLimiter(0));
    }

    @Test
    void attachmentAcumulaCabecalhosSemAlterarOOriginal() {
        Attachment base = Attachment.of("r.csv", "text/csv");
        Attachment com = base.withHeader("X-A", "1").withHeader("X-B", "2");

        assertEquals(Map.of(), base.headers());
        assertEquals(Map.of("X-A", "1", "X-B", "2"), com.headers());
        assertThrows(NullPointerException.class, () -> Attachment.of(null, "text/csv"));
    }
}
