package com.kage.auth.domain.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 10, 0);

    private Credential credential() {
        return Credential.create(UUID.randomUUID(), "hash", NOW);
    }

    @Test
    void deveTravarNaQuintaTentativaErrada() {
        Credential credential = credential();
        for (int i = 0; i < 4; i++) credential.registerFailedAttempt(NOW);
        assertThat(credential.isLocked(NOW)).isFalse();

        credential.registerFailedAttempt(NOW);

        assertThat(credential.isLocked(NOW)).isTrue();
        assertThat(credential.getLockedUntil()).isEqualTo(NOW.plus(Credential.LOCK_DURATION));
    }

    @Test
    void travaDeveExpirarDepoisDe15Minutos() {
        Credential credential = credential();
        for (int i = 0; i < 5; i++) credential.registerFailedAttempt(NOW);

        assertThat(credential.isLocked(NOW.plusMinutes(14))).isTrue();
        assertThat(credential.isLocked(NOW.plusMinutes(15))).isFalse();
    }

    @Test
    void erroDepoisDaTravaExpirada_deveRecomecarAContagem() {
        Credential credential = credential();
        for (int i = 0; i < 5; i++) credential.registerFailedAttempt(NOW);

        credential.registerFailedAttempt(NOW.plusMinutes(16));

        assertThat(credential.getFailedAttempts()).isEqualTo(1);
        assertThat(credential.isLocked(NOW.plusMinutes(16))).isFalse();
    }

    @Test
    void loginComSucesso_deveZerarContador() {
        Credential credential = credential();
        credential.registerFailedAttempt(NOW);
        credential.registerFailedAttempt(NOW);

        credential.registerSuccessfulLogin(NOW);

        assertThat(credential.getFailedAttempts()).isZero();
        assertThat(credential.getLockedUntil()).isNull();
    }
}
