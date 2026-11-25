package com.kage.shared.infrastructure.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Idempotency-Key (modelo Stripe) nas rotas que movem dinheiro.
 *
 * Com o header, a primeira requisição executa e a resposta é guardada; o reenvio com a mesma chave
 * devolve a resposta guardada (header Idempotent-Replayed: true) sem executar de novo — um retry de
 * rede depois de timeout não vira dois PIX. Sem o header, nada muda.
 *
 * - Escopo por usuário: a mesma chave de dois clientes não colide.
 * - Mesma chave com outro corpo ou outra rota: 422 (é bug do cliente, não retry).
 * - Chave em andamento (duas requisições simultâneas, ou a app caiu no meio): 409. Nunca reexecuta
 *   na dúvida — em dinheiro, executar duas vezes é pior que pedir para o cliente consultar.
 * - 2xx e 4xx são guardados (repetir daria o mesmo resultado); 5xx libera a chave para novo retry.
 * - Válida por {@link #TTL}.
 *
 * Roda depois da autorização: requisição sem token ou sem permissão nem chega aqui.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyFilter.class);

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    static final Duration TTL = Duration.ofHours(24);
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_\\-:.]{1,64}");

    /** Rotas que movem (ou comprometem) dinheiro. */
    static final List<PathPattern> PROTECTED = List.of(
            "/api/pix/send", "/api/pix/scheduled", "/api/pix/recurring", "/api/pix/refunds",
            "/api/accounts/*/withdraw", "/api/accounts/*/deposit",
            "/api/cards/*/purchases", "/api/cards/*/invoices/*/pay", "/api/cards/*/invoices/*/installment-plan",
            "/api/savings-boxes/*/deposit", "/api/savings-boxes/*/withdraw",
            "/api/accounts/*/boleto-payments", "/api/accounts/*/boletos"
    ).stream().map(PathPatternParser.defaultInstance::parse).toList();

    private final IdempotencyRepository repository;
    private final Clock clock;

    public IdempotencyFilter(IdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null) return true;
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        return PROTECTED.stream().noneMatch(pattern -> pattern.matches(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER).trim();
        if (!VALID_KEY.matcher(key).matches()) {
            writeError(response, request, HttpStatus.BAD_REQUEST,
                    HEADER + " inválida: use de 1 a 64 caracteres entre letras, números e - _ : .");
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) { // segurança decide; aqui só passa
            chain.doFilter(request, response);
            return;
        }

        String owner = authentication.getName();
        CachedBodyRequest cached = new CachedBodyRequest(request);
        String hash = sha256(cached.body());
        String path = request.getRequestURI();
        LocalDateTime now = LocalDateTime.now(clock);

        repository.deleteIfExpired(owner, key, now);
        if (!repository.tryReserve(owner, key, request.getMethod(), path, hash, now, now.plus(TTL))) {
            handleExisting(repository.find(owner, key), path, hash, request, response);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cached, wrapped);
        } catch (IOException | ServletException | RuntimeException e) {
            repository.release(owner, key);
            throw e;
        }

        int status = wrapped.getStatus();
        if (status >= 500) {
            repository.release(owner, key);
        } else {
            repository.complete(owner, key, status, new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
        }
        wrapped.copyBodyToResponse();
    }

    private void handleExisting(Optional<IdempotencyRepository.Entry> existing, String path, String hash,
                                HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (existing.isEmpty()) {
            // liberada por um 5xx entre o INSERT e a leitura: pede novo envio em vez de arriscar
            writeError(response, request, HttpStatus.CONFLICT, "Requisição em processamento. Tente novamente em instantes");
            return;
        }
        IdempotencyRepository.Entry entry = existing.get();
        if (!entry.path().equals(path) || !entry.requestHash().equals(hash)) {
            writeError(response, request, HttpStatus.UNPROCESSABLE_ENTITY,
                    HEADER + " já usada em outra requisição. Gere uma chave nova para cada operação");
            return;
        }
        if (entry.status() == IdempotencyRepository.Status.IN_PROGRESS) {
            logger.warn("Idempotency-Key {} reenviada com a primeira requisição ainda em andamento", entry.key());
            writeError(response, request, HttpStatus.CONFLICT,
                    "Requisição com esta " + HEADER + " ainda em processamento. Consulte o resultado antes de reenviar");
            return;
        }
        response.setStatus(entry.responseStatus());
        response.setHeader(REPLAYED_HEADER, "true");
        if (entry.responseBody() != null && !entry.responseBody().isEmpty()) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(entry.responseBody());
        }
    }

    private static void writeError(HttpServletResponse response, HttpServletRequest request, HttpStatus status,
                                   String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":" + status.value() + ",\"error\":\"" + status.getReasonPhrase()
                + "\",\"message\":\"" + message.replace("\"", "\\\"") + "\",\"path\":\"" + request.getRequestURI()
                + "\",\"timestamp\":\"" + LocalDateTime.now() + "\",\"fieldErrors\":[]}");
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
