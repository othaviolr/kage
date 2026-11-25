package com.kage.payment;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.application.usecase.WithdrawAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.payment.application.usecase.ApprovePixRefund;
import com.kage.payment.application.usecase.RegisterPixKey;
import com.kage.payment.application.usecase.RequestPixRefund;
import com.kage.payment.application.usecase.SendPix;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.enums.RefundStatus;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Estorno de ponta a ponta: PIX concluído -> estorno solicitado e aprovado -> Account devolve o valor
 * de quem recebeu para quem pagou -> Payment marca o estorno como COMPLETED. E o caminho triste: quem
 * recebeu já gastou o dinheiro, o estorno vira FAILED e nenhum saldo muda.
 */
class PixRefundIntegrationTest extends AbstractIntegrationTest {

    @Autowired CreateAccount createAccount;
    @Autowired DepositAccount depositAccount;
    @Autowired WithdrawAccount withdrawAccount;
    @Autowired RegisterPixKey registerPixKey;
    @Autowired SendPix sendPix;
    @Autowired RequestPixRefund requestPixRefund;
    @Autowired ApprovePixRefund approvePixRefund;
    @Autowired AccountRepository accountRepository;
    @Autowired PixTransactionRepository pixTransactionRepository;
    @Autowired PixRefundRepository pixRefundRepository;

    private record Transfer(UUID sourceAccountId, UUID targetAccountId, UUID transactionId) {}

    private Transfer completedPix(String amount) {
        var source = createAccount.execute(new CreateAccount.Input(newActiveCustomerId(), "CHECKING"));
        var target = createAccount.execute(new CreateAccount.Input(newActiveCustomerId(), "CHECKING"));
        depositAccount.execute(new DepositAccount.Input(source.accountId(), new BigDecimal("500.00")));

        String key = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(target.accountId(), PixKeyType.RANDOM, key));
        SendPix.Output pix = sendPix.execute(new SendPix.Input(source.accountId(), key, Money.of(amount), "compra"));

        awaitUntil(() -> pixTransactionRepository.findById(pix.transactionId())
                .map(t -> t.getStatus() == PixTransactionStatus.COMPLETED).orElse(false), Duration.ofSeconds(15));
        return new Transfer(source.accountId(), target.accountId(), pix.transactionId());
    }

    private RefundStatus awaitRefundOutcome(UUID refundId) {
        awaitUntil(() -> pixRefundRepository.findById(refundId)
                .map(r -> r.getRefundStatus() == RefundStatus.COMPLETED || r.getRefundStatus() == RefundStatus.FAILED)
                .orElse(false), Duration.ofSeconds(15));
        return pixRefundRepository.findById(refundId).orElseThrow().getRefundStatus();
    }

    @Test
    void estornoAprovado_deveDevolverODinheiroAoPagador() {
        Transfer pix = completedPix("150.00");

        var refund = requestPixRefund.execute(new RequestPixRefund.Input(pix.transactionId(), "produto não entregue", null));
        approvePixRefund.execute(refund.refundId(), "admin");

        assertThat(awaitRefundOutcome(refund.refundId())).isEqualTo(RefundStatus.COMPLETED);
        assertThat(accountRepository.findById(pix.sourceAccountId()).orElseThrow().getBalance().amount())
                .isEqualByComparingTo("500.00");
        assertThat(accountRepository.findById(pix.targetAccountId()).orElseThrow().getBalance().amount())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void estornoAprovado_deveFalharSemMexerEmSaldo_quandoRecebedorJaGastou() {
        Transfer pix = completedPix("150.00");
        withdrawAccount.execute(new WithdrawAccount.Input(pix.targetAccountId(), new BigDecimal("150.00")));

        var refund = requestPixRefund.execute(new RequestPixRefund.Input(pix.transactionId(), "golpe", null));
        approvePixRefund.execute(refund.refundId(), "admin");

        assertThat(awaitRefundOutcome(refund.refundId())).isEqualTo(RefundStatus.FAILED);
        assertThat(pixRefundRepository.findById(refund.refundId()).orElseThrow().getFailureReason())
                .contains("Saldo");
        assertThat(accountRepository.findById(pix.sourceAccountId()).orElseThrow().getBalance().amount())
                .isEqualByComparingTo("350.00");
        assertThat(accountRepository.findById(pix.targetAccountId()).orElseThrow().getBalance().amount())
                .isEqualByComparingTo("0.00");
    }
}
