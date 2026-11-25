package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.NotFoundException;

import java.util.List;
import java.util.UUID;

/** Agendamentos pendentes de uma conta: listar e cancelar (só até a execução). */
public class ManageScheduledPix {

    private final PixTransactionRepository pixTransactionRepository;

    public ManageScheduledPix(PixTransactionRepository pixTransactionRepository) {
        this.pixTransactionRepository = pixTransactionRepository;
    }

    public List<SchedulePix.Output> listPending(UUID accountId) {
        return pixTransactionRepository.findPendingScheduledByAccount(accountId).stream().map(SchedulePix::toOutput).toList();
    }

    /** Já executado (ou PIX imediato) não se cancela: aí o caminho é o estorno. */
    public SchedulePix.Output cancel(UUID transactionId) {
        PixTransaction transaction = pixTransactionRepository.findById(transactionId)
                .filter(PixTransaction::isScheduled)
                .orElseThrow(() -> new NotFoundException("PIX agendado não encontrado"));

        transaction.cancel(); // só PENDING; executado ou falho lança DomainException (422)
        return SchedulePix.toOutput(pixTransactionRepository.save(transaction));
    }
}
