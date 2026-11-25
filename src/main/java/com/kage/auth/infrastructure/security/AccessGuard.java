package com.kage.auth.infrastructure.security;

import com.kage.account.domain.repository.AccountRepository;
import com.kage.card.domain.repository.CardRepository;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.UnauthorizedException;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * Autorização por titularidade: o cliente só enxerga e movimenta o que é dele; o ADMIN passa direto.
 *
 * Recurso de outro cliente responde 404 com a mesma mensagem de "não existe" do use case — 403
 * confirmaria para um curioso que aquele id existe. Recurso que de fato não existe passa pelo guard
 * e o próprio use case devolve o 404.
 */
public class AccessGuard {

    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final PixKeyRepository pixKeyRepository;
    private final PixTransactionRepository pixTransactionRepository;
    private final PixRefundRepository pixRefundRepository;

    public AccessGuard(AccountRepository accountRepository, CardRepository cardRepository,
                       PixKeyRepository pixKeyRepository, PixTransactionRepository pixTransactionRepository,
                       PixRefundRepository pixRefundRepository) {
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.pixKeyRepository = pixKeyRepository;
        this.pixTransactionRepository = pixTransactionRepository;
        this.pixRefundRepository = pixRefundRepository;
    }

    public AuthenticatedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new UnauthorizedException("Autenticação necessária");
        }
        return user;
    }

    public void checkCustomer(UUID customerId) {
        AuthenticatedUser user = currentUser();
        if (user.isAdmin()) return;
        if (customerId == null) throw new ValidationException("Cliente é obrigatório");
        if (!customerId.equals(user.customerId())) throw new NotFoundException("Cliente não encontrado");
    }

    public void checkAccount(UUID accountId) {
        AuthenticatedUser user = currentUser();
        if (user.isAdmin()) return;
        if (accountId == null) throw new ValidationException("Conta é obrigatória");
        if (!ownsAccount(user, accountId)) throw new NotFoundException("Conta não encontrada");
    }

    public void checkCard(UUID cardId) {
        AuthenticatedUser user = currentUser();
        if (user.isAdmin()) return;
        if (cardId == null) throw new ValidationException("Cartão é obrigatório");
        boolean denied = cardRepository.findById(cardId)
                .map(card -> !card.getCustomerId().equals(user.customerId()))
                .orElse(false);
        if (denied) throw new NotFoundException("Cartão não encontrado");
    }

    public void checkPixKey(UUID pixKeyId) {
        AuthenticatedUser user = currentUser();
        if (user.isAdmin()) return;
        boolean denied = pixKeyRepository.findById(pixKeyId)
                .map(key -> !ownsExistingAccount(user, key.getAccountId()))
                .orElse(false);
        if (denied) throw new NotFoundException("Chave PIX não encontrada");
    }

    /** Quem enviou ou quem recebeu enxerga a transação. */
    public void checkPixTransaction(UUID transactionId) {
        AuthenticatedUser user = currentUser();
        if (user.isAdmin()) return;
        if (transactionId == null) throw new ValidationException("Transação é obrigatória");
        Optional<PixTransaction> transaction = pixTransactionRepository.findById(transactionId);
        if (transaction.isPresent() && !isParty(user, transaction.get())) {
            throw new NotFoundException("Transação PIX não encontrada");
        }
    }

    /** Só quem pagou o PIX original pede estorno. */
    public void checkCanRequestRefund(UUID originalTransactionId) {
        AuthenticatedUser user = currentUser();
        if (user.isAdmin()) return;
        if (originalTransactionId == null) throw new ValidationException("Transação original é obrigatória");
        boolean denied = pixTransactionRepository.findById(originalTransactionId)
                .map(t -> !ownsExistingAccount(user, t.getSourceAccountId()))
                .orElse(false);
        if (denied) throw new NotFoundException("Transação PIX não encontrada");
    }

    /** Só a conta de origem mexe no agendamento; mesma mensagem do "não existe" do ManageScheduledPix. */
    public void checkScheduledPix(UUID transactionId) {
        AuthenticatedUser user = currentUser();
        if (user.isAdmin()) return;
        boolean denied = pixTransactionRepository.findById(transactionId)
                .map(t -> !ownsExistingAccount(user, t.getSourceAccountId()))
                .orElse(false);
        if (denied) throw new NotFoundException("PIX agendado não encontrado");
    }

    public void checkPixRefund(UUID refundId) {
        AuthenticatedUser user = currentUser();
        if (user.isAdmin()) return;
        boolean denied = pixRefundRepository.findById(refundId)
                .flatMap(refund -> pixTransactionRepository.findById(refund.getOriginalTransactionId()))
                .map(t -> !isParty(user, t))
                .orElse(false);
        if (denied) throw new NotFoundException("Estorno não encontrado");
    }

    /** Aqui a conta precisa existir E ser do cliente: o "inexistente passa" do ownsAccount não vale. */
    private boolean isParty(AuthenticatedUser user, PixTransaction transaction) {
        return ownsExistingAccount(user, transaction.getSourceAccountId())
                || (transaction.getTargetAccountId() != null && ownsExistingAccount(user, transaction.getTargetAccountId()));
    }

    private boolean ownsExistingAccount(AuthenticatedUser user, UUID accountId) {
        return accountRepository.findById(accountId)
                .map(account -> account.getCustomerId().equals(user.customerId()))
                .orElse(false);
    }

    /** Conta inexistente conta como "do cliente": quem responde 404 é o use case. */
    private boolean ownsAccount(AuthenticatedUser user, UUID accountId) {
        return accountRepository.findById(accountId)
                .map(account -> account.getCustomerId().equals(user.customerId()))
                .orElse(true);
    }
}
