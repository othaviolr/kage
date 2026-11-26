package com.kage.boleto.application.usecase;

import com.kage.boleto.domain.repository.BoletoPaymentRepository;
import com.kage.boleto.domain.repository.BoletoPaymentRepository.Kind;
import com.kage.boleto.domain.repository.BoletoPaymentRepository.Payment;
import com.kage.boleto.domain.repository.BoletoRepository;
import com.kage.boleto.domain.service.BoletoAccountGateway;
import com.kage.boleto.domain.valueobject.BoletoCode;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.DocumentMask;
import com.kage.shared.domain.valueobject.ReceiptCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Comprovante de pagamento de boleto: linha digitável, quem recebeu, vencimento, valor original,
 * encargos e o total, com código de autenticação. Boleto do Kage traz o beneficiário com o CPF
 * mascarado; de outro banco, o banco emissor (sem a CIP, o Kage não sabe quem é o beneficiário).
 */
public class BoletoReceipts {

    public record Payer(String name, String document, String branch, String account) {}

    public record Beneficiary(String name, String document, String bankCode) {}

    public record Receipt(String type, UUID paymentId, String digitableLine, String barcode, Beneficiary beneficiary,
                          Payer payer, LocalDate dueDate, BigDecimal faceAmount, BigDecimal fine, BigDecimal interest,
                          BigDecimal paidAmount, LocalDateTime paidAt, String authenticationCode) {}

    private final BoletoPaymentRepository payments;
    private final BoletoRepository boletos;
    private final BoletoAccountGateway accounts;

    public BoletoReceipts(BoletoPaymentRepository payments, BoletoRepository boletos, BoletoAccountGateway accounts) {
        this.payments = payments;
        this.boletos = boletos;
        this.accounts = accounts;
    }

    public Receipt of(UUID paymentId) {
        Payment p = payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Pagamento de boleto não encontrado"));
        BoletoCode code = BoletoCode.parse(p.barcode());

        BoletoAccountGateway.AccountInfo payer = accounts.account(p.payerAccountId())
                .orElseThrow(() -> new IllegalStateException("Conta " + p.payerAccountId() + " do pagamento não existe mais"));
        String beneficiaryDocument = null;
        if (p.kind() == Kind.INTERNAL && p.boletoId() != null) {
            beneficiaryDocument = boletos.findById(p.boletoId())
                    .flatMap(b -> accounts.account(b.getBeneficiaryAccountId()))
                    .map(a -> DocumentMask.mask(a.holderDocument()))
                    .orElse(null);
        }
        String beneficiaryName = p.beneficiaryName() != null ? p.beneficiaryName() : "Banco " + p.bankCode();

        return new Receipt("BOLETO", p.id(), code.formattedDigitableLine(), p.barcode(),
                new Beneficiary(beneficiaryName, beneficiaryDocument, p.bankCode()),
                new Payer(payer.holderName(), DocumentMask.mask(payer.holderDocument()), payer.branch(), payer.accountNumber()),
                p.dueDate(), p.faceAmount(), p.fine(), p.interest(), p.paidAmount(), p.paidAt(),
                ReceiptCode.of("BOLETO", p.id(), p.barcode(), p.paidAmount(), p.paidAt(), p.payerAccountId()));
    }
}
