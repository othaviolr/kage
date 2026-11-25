package com.kage.auth.application.usecase;

import com.kage.auth.domain.service.PasswordHasher;
import com.kage.auth.domain.service.TokenIssuer;
import com.kage.shared.domain.exception.UnauthorizedException;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Login do backoffice. O operador vem da configuração (kage.security.admin.*): num banco real isso
 * seria SSO corporativo; aqui basta um usuário com papel ADMIN, que também simula os sistemas
 * externos (entrada de dinheiro, bandeira do cartão).
 */
public class AdminLogin {

    public record Input(String username, String password) {}

    private final String adminUsername;
    private final String adminPasswordHash;
    private final PasswordHasher passwordHasher;
    private final TokenIssuer tokenIssuer;

    public AdminLogin(String adminUsername, String adminPasswordHash, PasswordHasher passwordHasher, TokenIssuer tokenIssuer) {
        this.adminUsername = adminUsername;
        this.adminPasswordHash = adminPasswordHash;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
    }

    public Login.Output execute(Input input) {
        String username = input.username() == null ? "" : input.username();
        String password = input.password() == null ? "" : input.password();

        // compara usuário em tempo constante e sempre roda o BCrypt, acertando o usuário ou não
        boolean userMatches = MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8),
                adminUsername.getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches = passwordHasher.matches(password, adminPasswordHash);

        if (!userMatches || !passwordMatches) {
            throw new UnauthorizedException("Usuário ou senha inválidos");
        }

        TokenIssuer.IssuedToken token = tokenIssuer.issueForAdmin(adminUsername);
        return new Login.Output(token.accessToken(), "Bearer", token.expiresAt());
    }
}
