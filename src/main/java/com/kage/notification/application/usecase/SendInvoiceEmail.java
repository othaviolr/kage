package com.kage.notification.application.usecase;

import com.kage.notification.domain.repository.EmailLogRepository;
import com.kage.notification.domain.repository.EmailLogRepository.EmailLog;
import com.kage.notification.domain.repository.EmailLogRepository.Status;
import com.kage.notification.domain.service.EmailChannel;
import com.kage.notification.domain.service.InvoicePdfProvider;
import com.kage.notification.domain.service.RecipientDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Fatura por e-mail no fechamento, com o PDF anexo (a "fatura digital").
 *
 * Idempotente pelo evento: o registro do envio é criado antes (PENDING) e marcado SENT depois. Na
 * reentrega, SENT não manda de novo; PENDING ou FAILED tenta outra vez — o envio que caiu no meio não
 * se perde. Falha no envio volta a exceção para a mensagem ser reentregue (e ir para a DLQ se insistir).
 */
public class SendInvoiceEmail {

    private static final Logger logger = LoggerFactory.getLogger(SendInvoiceEmail.class);
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    static final String KIND = "INVOICE_CLOSED";

    public record Input(UUID cardId, UUID invoiceId, String referenceMonth, BigDecimal total, LocalDate dueDate,
                        String sourceEventId) {}

    public record Item(UUID id, String kind, String to, String subject, String attachmentName, Integer attachmentSize,
                       String attachmentSha256, UUID referenceId, String status, String failureReason,
                       LocalDateTime createdAt, LocalDateTime sentAt) {}

    private final RecipientDirectory recipients;
    private final InvoicePdfProvider pdfs;
    private final EmailChannel channel;
    private final EmailLogRepository logRepository;
    private final Clock clock;

    public SendInvoiceEmail(RecipientDirectory recipients, InvoicePdfProvider pdfs, EmailChannel channel,
                            EmailLogRepository logRepository, Clock clock) {
        this.recipients = recipients;
        this.pdfs = pdfs;
        this.channel = channel;
        this.logRepository = logRepository;
        this.clock = clock;
    }

    /** @return true se mandou agora; false se não havia o que mandar ou já tinha sido mandado */
    public boolean execute(Input input) {
        String eventId = input.sourceEventId() != null ? input.sourceEventId() : UUID.randomUUID().toString();
        Optional<EmailLog> previous = logRepository.findBySourceEventId(eventId);
        if (previous.isPresent() && previous.get().status() == Status.SENT) {
            logger.debug("Fatura do evento {} já foi enviada por e-mail", eventId);
            return false;
        }

        Optional<UUID> customerId = recipients.customerOfCard(input.cardId());
        Optional<String> address = customerId.flatMap(recipients::emailOf);
        if (customerId.isEmpty() || address.isEmpty()) {
            logger.warn("Fatura {} sem destinatário de e-mail: cartão ou cliente não encontrado", input.invoiceId());
            return false;
        }
        Optional<InvoicePdfProvider.Pdf> pdf = pdfs.pdfOf(input.cardId(), input.referenceMonth());
        if (pdf.isEmpty()) {
            logger.warn("Fatura {} de {} sem PDF (não encontrada ou aberta): e-mail não enviado", input.invoiceId(),
                    input.referenceMonth());
            return false;
        }

        String month = YearMonth.parse(input.referenceMonth()).format(MONTH);
        String subject = "Sua fatura Kage de " + month + " fechou: " + money(input.total()) + ", vence em "
                + input.dueDate().format(DATE);
        EmailChannel.Email email = new EmailChannel.Email(address.get(), subject, body(month, input),
                new EmailChannel.Attachment(pdf.get().fileName(), "application/pdf", pdf.get().content()));

        EmailLog log = previous.orElseGet(() -> new EmailLog(UUID.randomUUID(), customerId.get(), KIND, address.get(),
                subject, pdf.get().fileName(), pdf.get().content().length, sha256(pdf.get().content()), input.invoiceId(),
                eventId, Status.PENDING, null, LocalDateTime.now(clock), null));
        if (previous.isEmpty() && !logRepository.insertIfNew(log)) {
            return false; // outra entrega do mesmo evento chegou antes
        }

        try {
            channel.send(email);
        } catch (RuntimeException e) {
            logRepository.markFailed(log.id(), e.getMessage());
            throw e; // a mensagem volta para a fila: tenta de novo
        }
        logRepository.markSent(log.id(), LocalDateTime.now(clock));
        return true;
    }

    /** Envios ao cliente, para o backoffice (auditoria da comunicação). */
    public List<Item> sentTo(UUID customerId) {
        return logRepository.findByCustomer(customerId, 50).stream()
                .map(l -> new Item(l.id(), l.kind(), l.toAddress(), l.subject(), l.attachmentName(), l.attachmentSize(),
                        l.attachmentSha256(), l.referenceId(), l.status().name(), l.failureReason(), l.createdAt(), l.sentAt()))
                .toList();
    }

    private static String body(String month, Input input) {
        return "Olá!\n\n"
                + "A fatura do seu cartão Kage de " + month + " fechou em " + money(input.total())
                + " e vence em " + input.dueDate().format(DATE) + ".\n\n"
                + "O PDF com os lançamentos, o pagamento mínimo e as taxas de juros está anexo. "
                + "Você paga pelo app, com o saldo da conta, ou ativa o débito automático.\n\n"
                + "Pagando menos que o total, o saldo vai para o rotativo na próxima fatura, com juros.\n\n"
                + "Kage nunca pede senha, código ou dados do cartão por e-mail.\n";
    }

    private static String money(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(PT_BR).format(amount).replace(' ', ' ');
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
