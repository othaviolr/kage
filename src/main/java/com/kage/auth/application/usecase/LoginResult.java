package com.kage.auth.application.usecase;

/** Resultado do login: os tokens (aparelho confiável) ou o desafio do código (aparelho novo). */
public record LoginResult(AuthTokens tokens, DeviceVerification.LoginChallenge challenge) {

    public static LoginResult signedIn(AuthTokens tokens) {
        return new LoginResult(tokens, null);
    }

    public static LoginResult verificationRequired(DeviceVerification.LoginChallenge challenge) {
        return new LoginResult(null, challenge);
    }

    public boolean isVerificationRequired() {
        return challenge != null;
    }
}
