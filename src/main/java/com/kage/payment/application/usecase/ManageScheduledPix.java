package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.NotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Agendamentos pendentes de uma conta: listar e cancelar (só até a execução). */
public class ManageScheduledPix {

    private final PixTransactionRepository pixTransactionRepository;
    private final PixRecurrenceChain recurrenceChain;

    public ManageScheduledPix(PixTransactionRepository pixTransactionRepository, PixRecurrenceChain recurrenceChain) {
        this.pixTransactionRepository = pixTransactionRepository;
        this.recurrenceChain = recurrenceChain;
    }

    public List<SchedulePix.Output> listPending(UUID accountId) {
        return pixTransactionRepository.findPendingScheduledByAccount(accountId).stream().map(SchedulePix::toOutput).toList();
    }

    /**
     * Já executado (ou PIX imediato) não se cancela: aí o caminho é o estorno. Se for ocorrência de
     * um PIX recorrente, cancelar é pular esta vez: a próxima ocorrência é agendada.
     */
    @Transactional
    public SchedulePix.Output cancel(UUID transactionId) {
        PixTransaction transaction = pixTransactionRepository.findById(transactionId)
                .filter(PixTransaction::isScheduled)
                .orElseThrow(() -> new NotFoundException("PIX agendado não encontrado"));

        transaction.cancel(); // só PENDING; executado ou falho lança DomainException (422)
        PixTransaction saved = pixTransactionRepository.save(transaction);
        recurrenceChain.onOccurrenceResolved(saved);
        return SchedulePix.toOutput(saved);
    }
}
