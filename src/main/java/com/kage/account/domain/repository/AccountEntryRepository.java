package com.kage.account.domain.repository;

import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.EntryType;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AccountEntryRepository {

    void save(AccountEntry entry);

    /** Soma dos lançamentos de um tipo a partir de um instante (ex.: saques de hoje). */
    Money sumByTypeSince(UUID accountId, EntryType type, LocalDateTime since);

    /** Lançamentos no intervalo [from, to), mais recentes primeiro. */
    List<AccountEntry> findByAccountIdBetween(UUID accountId, LocalDateTime from, LocalDateTime to);
}
