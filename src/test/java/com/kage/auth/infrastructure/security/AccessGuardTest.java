package com.kage.auth.infrastructure.security;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.card.domain.repository.CardRepository;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.TransactionType;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.UnauthorizedException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessGuardTest {

    @Mock AccountRepository accountRepository;
    @Mock CardRepository cardRepository;
    @Mock PixKeyRepository pixKeyRepository;
    @Mock PixTransactionRepository pixTransactionRepository;
    @Mock PixRefundRepository pixRefundRepository;

    @InjectMocks AccessGuard guard;

    UUID maria;
    UUID joao;
    Account mariaAccount;
    Account joaoAccount;

    @BeforeEach
    void setUp() {
        maria = UUID.randomUUID();
        joao = UUID.randomUUID();
        mariaAccount = Account.create(maria, AccountType.CHECKING, "10000000", "2");
        joaoAccount = Account.create(joao, AccountType.CHECKING, "10000001", "0");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(UUID customerId) {
        var user = new AuthenticatedUser(customerId, customerId.toString(), AuthenticatedUser.Role.CUSTOMER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private void loginAsAdmin() {
        var user = new AuthenticatedUser(null, "admin", AuthenticatedUser.Role.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private PixTransaction pix(Account from, UUID toAccountId) {
        PixTransaction tx = PixTransaction.create(from.getId(), "chave", Money.of("10.00"), null, TransactionType.IMMEDIATE, null);
        tx.startProcessing(toAccountId);
        return tx;
    }

    @Test
    void semAutenticacao_deveLancarUnauthorized() {
        assertThatThrownBy(() -> guard.checkAccount(UUID.randomUUID())).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void admin_passaSemConsultarNada() {
        loginAsAdmin();

        guard.checkAccount(joaoAccount.getId());
        guard.checkPixTransaction(UUID.randomUUID());

        verifyNoInteractions(accountRepository, pixTransactionRepository);
    }

    @Test
    void contaDeOutro_deveResponder404() {
        loginAs(maria);
        when(accountRepository.findById(joaoAccount.getId())).thenReturn(Optional.of(joaoAccount));

        assertThatThrownBy(() -> guard.checkAccount(joaoAccount.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Conta não encontrada");
    }

    @Test
    void recebedor_deveVerATransacao() {
        loginAs(maria);
        PixTransaction tx = pix(joaoAccount, mariaAccount.getId());
        when(pixTransactionRepository.findById(tx.getTransactionId())).thenReturn(Optional.of(tx));
        when(accountRepository.findById(joaoAccount.getId())).thenReturn(Optional.of(joaoAccount));
        when(accountRepository.findById(mariaAccount.getId())).thenReturn(Optional.of(mariaAccount));

        assertThatCode(() -> guard.checkPixTransaction(tx.getTransactionId())).doesNotThrowAnyException();
    }

    @Test
    void recebedor_naoPodePedirEstorno_soQuemPagou() {
        loginAs(maria);
        PixTransaction tx = pix(joaoAccount, mariaAccount.getId());
        when(pixTransactionRepository.findById(tx.getTransactionId())).thenReturn(Optional.of(tx));
        when(accountRepository.findById(joaoAccount.getId())).thenReturn(Optional.of(joaoAccount));

        assertThatThrownBy(() -> guard.checkCanRequestRefund(tx.getTransactionId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void terceiro_naoVeTransacao_mesmoQueAContaDestinoNaoExistaMais() {
        loginAs(maria);
        UUID removedAccount = UUID.randomUUID();
        PixTransaction tx = pix(joaoAccount, removedAccount);
        when(pixTransactionRepository.findById(tx.getTransactionId())).thenReturn(Optional.of(tx));
        when(accountRepository.findById(joaoAccount.getId())).thenReturn(Optional.of(joaoAccount));
        when(accountRepository.findById(removedAccount)).thenReturn(Optional.empty());

        // conta inexistente não pode virar "acesso liberado"
        assertThatThrownBy(() -> guard.checkPixTransaction(tx.getTransactionId()))
                .isInstanceOf(NotFoundException.class);
    }
}
