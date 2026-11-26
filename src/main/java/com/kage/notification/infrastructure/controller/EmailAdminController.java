package com.kage.notification.infrastructure.controller;

import com.kage.notification.application.usecase.SendInvoiceEmail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Backoffice: e-mails enviados ao cliente (auditoria da comunicação). ADMIN pelo SecurityConfig. */
@RestController
@RequestMapping("/api/admin/emails")
public class EmailAdminController {

    private final SendInvoiceEmail sendInvoiceEmail;

    public EmailAdminController(SendInvoiceEmail sendInvoiceEmail) {
        this.sendInvoiceEmail = sendInvoiceEmail;
    }

    @GetMapping
    public ResponseEntity<List<SendInvoiceEmail.Item>> sentTo(@RequestParam UUID customerId) {
        return ResponseEntity.ok(sendInvoiceEmail.sentTo(customerId));
    }
}
