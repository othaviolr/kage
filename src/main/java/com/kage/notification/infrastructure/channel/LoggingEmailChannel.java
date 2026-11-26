package com.kage.notification.infrastructure.channel;

import com.kage.notification.domain.service.EmailChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canal de e-mail simulado: registra no log o que seria enviado (destinatário mascarado). Para enviar
 * de verdade, basta outro bean EmailChannel (SES, SMTP...).
 */
public class LoggingEmailChannel implements EmailChannel {

    private static final Logger logger = LoggerFactory.getLogger(LoggingEmailChannel.class);

    @Override
    public void send(Email email) {
        logger.info("[e-mail] para={} assunto=\"{}\" anexo={} ({} bytes)", mask(email.to()), email.subject(),
                email.attachment() == null ? "-" : email.attachment().fileName(),
                email.attachment() == null ? 0 : email.attachment().content().length);
    }

    /** jo***@exemplo.com: o log não é lugar de dado pessoal inteiro (LGPD). */
    static String mask(String address) {
        int at = address.indexOf('@');
        if (at <= 0) return "***";
        String user = address.substring(0, at);
        return user.substring(0, Math.min(2, user.length())) + "***" + address.substring(at);
    }
}
