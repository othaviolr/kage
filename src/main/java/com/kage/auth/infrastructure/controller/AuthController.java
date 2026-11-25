package com.kage.auth.infrastructure.controller;

import com.kage.auth.application.usecase.AdminLogin;
import com.kage.auth.application.usecase.Login;
import com.kage.auth.application.usecase.SignUp;
import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.auth.infrastructure.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SignUp signUp;
    private final Login login;
    private final AdminLogin adminLogin;
    private final AccessGuard accessGuard;

    public AuthController(SignUp signUp, Login login, AdminLogin adminLogin, AccessGuard accessGuard) {
        this.signUp = signUp;
        this.login = login;
        this.adminLogin = adminLogin;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignUp.Output> signUp(@RequestBody SignUp.Input input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(signUp.execute(input));
    }

    @PostMapping("/login")
    public ResponseEntity<Login.Output> login(@RequestBody Login.Input input) {
        return ResponseEntity.ok(login.execute(input));
    }

    @PostMapping("/admin/login")
    public ResponseEntity<Login.Output> adminLogin(@RequestBody AdminLogin.Input input) {
        return ResponseEntity.ok(adminLogin.execute(input));
    }

    /** Quem sou eu, segundo o token: o app usa para saber o customerId depois do login. */
    @GetMapping("/me")
    public ResponseEntity<AuthenticatedUser> me() {
        return ResponseEntity.ok(accessGuard.currentUser());
    }
}
