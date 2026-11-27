package com.kage.card.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.card.application.usecase.CardTransactions;
import com.kage.card.infrastructure.export.CardTransactionsCsv;
import com.kage.card.infrastructure.export.CardTransactionsOfx;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Extrato do cartão por meses de fatura (até 12; sem período, as últimas 6 até a atual), com filtro por
 * tipo (PURCHASES, FEES, CREDITS, BALANCES; vários separados por vírgula), por categoria da compra
 * (SUPERMARKET, RESTAURANTS...) e por cartão virtual. Em
 * JSON, CSV (planilha) ou OFX (app de finanças).
 */
@RestController
public class CardTransactionsController {

    private final CardTransactions cardTransactions;
    private final AccessGuard accessGuard;

    public CardTransactionsController(CardTransactions cardTransactions, AccessGuard accessGuard) {
        this.cardTransactions = cardTransactions;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/cards/{id}/transactions")
    public ResponseEntity<CardTransactions.Output> list(@PathVariable UUID id,
                                                        @RequestParam(required = false) String from,
                                                        @RequestParam(required = false) String to,
                                                        @RequestParam(required = false) String type,
                                                        @RequestParam(required = false) UUID virtualCardId,
                                                        @RequestParam(required = false) String category) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(cardTransactions.list(id, from, to, type, virtualCardId, category));
    }

    @GetMapping("/api/cards/{id}/transactions.csv")
    public ResponseEntity<byte[]> csv(@PathVariable UUID id, @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) UUID virtualCardId,
                                      @RequestParam(required = false) String category) {
        accessGuard.checkCard(id);
        CardTransactions.Output out = cardTransactions.list(id, from, to, type, virtualCardId, category);
        return file(CardTransactionsCsv.CONTENT_TYPE, CardTransactionsCsv.fileName(out, "csv"), CardTransactionsCsv.write(out));
    }

    @GetMapping("/api/cards/{id}/transactions.ofx")
    public ResponseEntity<byte[]> ofx(@PathVariable UUID id, @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) UUID virtualCardId,
                                      @RequestParam(required = false) String category) {
        accessGuard.checkCard(id);
        CardTransactions.Output out = cardTransactions.list(id, from, to, type, virtualCardId, category);
        return file(CardTransactionsOfx.CONTENT_TYPE, CardTransactionsCsv.fileName(out, "ofx"), CardTransactionsOfx.write(out));
    }

    private static ResponseEntity<byte[]> file(String contentType, String name, String body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
