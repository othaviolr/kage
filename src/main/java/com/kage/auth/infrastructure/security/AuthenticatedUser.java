package com.kage.auth.infrastructure.security;

import java.util.UUID;

/**
 * Quem está chamando a API, extraído do JWT.
 *
 * @param customerId id do cliente; nulo para o ADMIN
 * @param username   CPF não vai no token (dado pessoal): para cliente é o id, para admin o usuário
 * @param sessionId  sessão a que o token pertence: logout/bloqueio a encerram e o token para de valer
 */
public record AuthenticatedUser(UUID customerId, String username, Role role, UUID sessionId) {

    public enum Role { CUSTOMER, ADMIN }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
