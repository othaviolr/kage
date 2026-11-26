package com.kage.notification.domain.enums;

/** Por que um aviso ficou só na caixa de entrada, sem push. */
public enum PushSilencedReason {
    /** O cliente desligou o push da categoria. */
    CATEGORY_DISABLED,
    /** Chegou dentro do horário silencioso do cliente. */
    QUIET_HOURS
}
