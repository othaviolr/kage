package com.kage.auth.infrastructure.controller;

import com.kage.auth.application.usecase.AdminLogin;
import com.kage.auth.application.usecase.AuthTokens;
import com.kage.auth.application.usecase.ChangePassword;
import com.kage.auth.application.usecase.ForgotPassword;
import com.kage.auth.application.usecase.ResetPassword;
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
    private final ChangePassword changePassword;
    private final ForgotPassword forgotPassword;
    private final ResetPassword resetPassword;

    public AuthController(SignUp signUp, Login login, AdminLogin adminLogin, SessionTokens sessionTokens,
                          ManageSessions manageSessions, AccessGuard accessGuard, ChangePassword changePassword,
                          ForgotPassword forgotPassword, ResetPassword resetPassword) {
        this.changePassword = changePassword;
        this.forgotPassword = forgotPassword;
        this.resetPassword = resetPassword;
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

    /** Troca de senha logado. Derruba as outras sessões e mantém esta. */
    @PostMapping("/password")
    public ResponseEntity<Map<String, Integer>> changePassword(@RequestBody ChangePasswordRequest request) {
        AuthenticatedUser user = requireCustomer();
        int revoked = changePassword.execute(new ChangePassword.Input(user.customerId(), user.sessionId(),
                request.currentPassword(), request.newPassword()));
        return ResponseEntity.ok(Map.of("otherSessionsRevoked", revoked));
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    /** Sempre 202, com a mesma mensagem, exista o CPF ou não. */
    @PostMapping("/password/forgot")
    public ResponseEntity<ForgotPassword.Output> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        return ResponseEntity.accepted().body(forgotPassword.execute(request.cpf()));
    }

    public record ForgotPasswordRequest(String cpf) {}

    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@RequestBody ResetPassword.Input input) {
        resetPassword.execute(input);
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
