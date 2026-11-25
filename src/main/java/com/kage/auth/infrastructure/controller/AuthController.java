package com.kage.auth.infrastructure.controller;

import com.kage.auth.application.usecase.AdminLogin;
import com.kage.auth.application.usecase.AuthTokens;
import com.kage.auth.application.usecase.Login;
import com.kage.auth.application.usecase.ManageSessions;
import com.kage.auth.application.usecase.SessionTokens;
import com.kage.auth.application.usecase.SignUp;
import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.auth.infrastructure.security.AuthenticatedUser;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SignUp signUp;
    private final Login login;
    private final AdminLogin adminLogin;
    private final SessionTokens sessionTokens;
    private final ManageSessions manageSessions;
    private final AccessGuard accessGuard;

    public AuthController(SignUp signUp, Login login, AdminLogin adminLogin, SessionTokens sessionTokens,
                          ManageSessions manageSessions, AccessGuard accessGuard) {
        this.signUp = signUp;
        this.login = login;
        this.adminLogin = adminLogin;
        this.sessionTokens = sessionTokens;
        this.manageSessions = manageSessions;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignUp.Output> signUp(@RequestBody SignUp.Input input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(signUp.execute(input));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokens> login(@RequestBody Login.Input input,
                                            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return ResponseEntity.ok(login.execute(input, userAgent));
    }

    @PostMapping("/admin/login")
    public ResponseEntity<AuthTokens> adminLogin(@RequestBody AdminLogin.Input input,
                                                 @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return ResponseEntity.ok(adminLogin.execute(input, userAgent));
    }

    /** Troca um refresh token por um par novo (o anterior deixa de valer). */
    @PostMapping("/refresh")
    public ResponseEntity<AuthTokens> refresh(@RequestBody RefreshRequest request) {
        return ResponseEntity.ok(sessionTokens.refresh(request.refreshToken()));
    }

    public record RefreshRequest(String refreshToken) {}

    /** Encerra a sessão deste token: ele e o refresh token dela param de valer na hora. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        manageSessions.logout(accessGuard.currentUser().sessionId());
        return ResponseEntity.noContent().build();
    }

    /** "Sair de todos os dispositivos", inclusive deste. */
    @PostMapping("/logout-all")
    public ResponseEntity<Map<String, Integer>> logoutAll() {
        AuthenticatedUser user = requireCustomer();
        return ResponseEntity.ok(Map.of("sessionsRevoked", manageSessions.logoutAll(user.customerId())));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<ManageSessions.SessionView>> sessions() {
        AuthenticatedUser user = requireCustomer();
        return ResponseEntity.ok(manageSessions.listActive(user.customerId(), user.sessionId()));
    }

    /** Encerra uma sessão específica (ex.: o celular que foi perdido). */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID sessionId) {
        AuthenticatedUser user = accessGuard.currentUser();
        manageSessions.revokeSession(sessionId, user.isAdmin() ? null : user.customerId());
        return ResponseEntity.noContent().build();
    }

    /** Quem sou eu, segundo o token: o app usa para saber o customerId depois do login. */
    @GetMapping("/me")
    public ResponseEntity<AuthenticatedUser> me() {
        return ResponseEntity.ok(accessGuard.currentUser());
    }

    private AuthenticatedUser requireCustomer() {
        AuthenticatedUser user = accessGuard.currentUser();
        if (user.isAdmin()) throw new ValidationException("Disponível só para clientes");
        return user;
    }
}
