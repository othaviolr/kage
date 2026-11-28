package com.kage.shared.domain.pix;

import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PixBrCodeTest {

    @Test
    void exemploDoManualDoBancoCentral() {
        // Manual de Padrões para Iniciação do PIX (BCB): chave aleatória, sem valor, sem txid
        assertThat(PixBrCode.of("123e4567-e12b-12d1-a456-426655440000", null, "Fulano de Tal", "BRASILIA", null, false))
                .isEqualTo("00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000"
                        + "5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***63041D3D");
    }

    @Test
    void comValor_txid_eUsoUnico() {
        String code = PixBrCode.of("5b1e0c6a-2f4d-4c8e-9a7b-3d2f1e0c9b8a", new BigDecimal("1000.00"), "KAGE", "SAO PAULO",
                "FATABC123", true);
        assertThat(code).startsWith("000201010212") // versão 01, uso único
                .contains("54071000.00")
                .contains("5802BR5904KAGE6009SAO PAULO")
                .contains("62130509FATABC123")
                .matches(".*6304[0-9A-F]{4}$");
        // o CRC é de tudo o que vem antes, inclusive o "6304"
        assertThat(code.substring(code.length() - 4)).isEqualTo(PixBrCode.crc16(code.substring(0, code.length() - 4)));
    }

    @Test
    void nomeEcidadeSemAcento_eNoTamanhoDoCampo() {
        assertThat(PixBrCode.ascii("São José dos Campos", 15)).isEqualTo("Sao Jose dos Ca");
    }

    @Test
    void txidInvalido() {
        assertThatThrownBy(() -> PixBrCode.of("chave", BigDecimal.ONE, "KAGE", "SP", "com espaço", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- leitura (o copia e cola que o cliente cola para pagar) ----

    private static final String BCB_EXAMPLE = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000"
            + "5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***63041D3D";

    @Test
    void le_oExemploDoBancoCentral_semValorESemTxid() {
        PixBrCode.Parsed p = PixBrCode.parse(BCB_EXAMPLE);
        assertThat(p.pixKey()).isEqualTo("123e4567-e12b-12d1-a456-426655440000");
        assertThat(p.amount()).isNull();
        assertThat(p.txid()).isNull();
        assertThat(p.merchantName()).isEqualTo("Fulano de Tal");
        assertThat(p.city()).isEqualTo("BRASILIA");
        assertThat(p.singleUse()).isFalse();
    }

    @Test
    void le_oQueOKageGera_idaEVolta() {
        String code = PixBrCode.of("5b1e0c6a-2f4d-4c8e-9a7b-3d2f1e0c9b8a", new BigDecimal("1234.5"), "KAGE", "SAO PAULO",
                "FATABC123", true);
        PixBrCode.Parsed p = PixBrCode.parse("  " + code + "\n");
        assertThat(p.pixKey()).isEqualTo("5b1e0c6a-2f4d-4c8e-9a7b-3d2f1e0c9b8a");
        assertThat(p.amount()).isEqualByComparingTo("1234.50");
        assertThat(p.txid()).isEqualTo("FATABC123");
        assertThat(p.singleUse()).isTrue();
    }

    @Test
    void crcEmMinusculas_valeIgual() {
        String lower = BCB_EXAMPLE.substring(0, BCB_EXAMPLE.length() - 4) + "1d3d";
        assertThat(PixBrCode.parse(lower).pixKey()).isEqualTo("123e4567-e12b-12d1-a456-426655440000");
    }

    @Test
    void mensagemDoRecebedor_eGuiEmMaiusculas() {
        String account = PixBrCode.field("00", "BR.GOV.BCB.PIX") + PixBrCode.field("01", "maria@kage.com")
                + PixBrCode.field("02", "Aluguel de maio");
        String code = withCrc(PixBrCode.field("00", "01") + PixBrCode.field("26", account) + PixBrCode.field("52", "0000")
                + PixBrCode.field("53", "986") + PixBrCode.field("54", "0.00") + PixBrCode.field("58", "BR")
                + PixBrCode.field("59", "MARIA") + PixBrCode.field("60", "RIO") + PixBrCode.field("62", PixBrCode.field("05", "ALUGUEL05")));
        PixBrCode.Parsed p = PixBrCode.parse(code);
        assertThat(p.pixKey()).isEqualTo("maria@kage.com");
        assertThat(p.info()).isEqualTo("Aluguel de maio");
        assertThat(p.amount()).as("0.00 = sem valor").isNull();
        assertThat(p.txid()).isEqualTo("ALUGUEL05");
    }

    @Test
    void codigoCortadoOuAlterado_crcNaoBate() {
        assertThatThrownBy(() -> PixBrCode.parse(BCB_EXAMPLE.substring(0, BCB_EXAMPLE.length() - 1)))
                .isInstanceOf(ValidationException.class).hasMessage(PixBrCode.INVALID);
        assertThatThrownBy(() -> PixBrCode.parse(BCB_EXAMPLE.replace("Fulano", "Ciclano")))
                .isInstanceOf(ValidationException.class).hasMessage(PixBrCode.INVALID);
        assertThatThrownBy(() -> PixBrCode.parse("qualquer coisa"))
                .isInstanceOf(ValidationException.class).hasMessage(PixBrCode.INVALID);
        assertThatThrownBy(() -> PixBrCode.parse("  "))
                .isInstanceOf(ValidationException.class).hasMessage("Cole o código PIX copia e cola");
    }

    @Test
    void tamanhoDeCampoErrado_mesmoComCrcCerto() {
        assertThatThrownBy(() -> PixBrCode.parse(withCrc("000201269" + "9")))
                .isInstanceOf(ValidationException.class).hasMessage(PixBrCode.INVALID);
    }

    @Test
    void qrDinamico_comUrl_aindaNaoAceito() {
        String account = PixBrCode.field("00", "br.gov.bcb.pix") + PixBrCode.field("25", "pix.exemplo.com/qr/v2/abc");
        String code = withCrc(PixBrCode.field("00", "01") + PixBrCode.field("26", account) + PixBrCode.field("53", "986")
                + PixBrCode.field("58", "BR") + PixBrCode.field("59", "LOJA") + PixBrCode.field("60", "SP"));
        assertThatThrownBy(() -> PixBrCode.parse(code)).isInstanceOf(ValidationException.class)
                .hasMessageContaining("QR Code dinâmico");
    }

    @Test
    void outraMoeda_ouSemContaPix() {
        String account = PixBrCode.field("00", "br.gov.bcb.pix") + PixBrCode.field("01", "chave");
        assertThatThrownBy(() -> PixBrCode.parse(withCrc(PixBrCode.field("00", "01") + PixBrCode.field("26", account)
                + PixBrCode.field("53", "840") + PixBrCode.field("59", "X") + PixBrCode.field("60", "Y"))))
                .isInstanceOf(ValidationException.class).hasMessage("Só é possível pagar PIX em reais");
        String other = PixBrCode.field("00", "com.outro.arranjo") + PixBrCode.field("01", "chave");
        assertThatThrownBy(() -> PixBrCode.parse(withCrc(PixBrCode.field("00", "01") + PixBrCode.field("26", other)
                + PixBrCode.field("53", "986"))))
                .isInstanceOf(ValidationException.class).hasMessage("Este código não é de PIX");
    }

    @Test
    void valorMalFormado() {
        String account = PixBrCode.field("00", "br.gov.bcb.pix") + PixBrCode.field("01", "chave");
        assertThatThrownBy(() -> PixBrCode.parse(withCrc(PixBrCode.field("00", "01") + PixBrCode.field("26", account)
                + PixBrCode.field("53", "986") + PixBrCode.field("54", "10,50"))))
                .isInstanceOf(ValidationException.class).hasMessage(PixBrCode.INVALID);
    }

    @Test
    void mensagemParaQuemPaga_idaEVolta_semAcento() {
        String code = PixBrCode.of("maria@kage.com", null, "Maria", "Rio", "KG1", false, "Pão de sábado");
        PixBrCode.Parsed p = PixBrCode.parse(code);
        assertThat(p.info()).isEqualTo("Pao de sabado");
        assertThat(p.amount()).isNull();
        assertThat(PixBrCode.of("maria@kage.com", null, "Maria", "Rio", "KG1", false, "  "))
                .isEqualTo(PixBrCode.of("maria@kage.com", null, "Maria", "Rio", "KG1", false));
    }

    @Test
    void mensagemCabeNoQueSobraDaContaDoRecebedor() {
        String key = "123e4567-e12b-12d1-a456-426655440000"; // 36
        int room = PixBrCode.maxInfoLength(key);
        assertThat(room).isEqualTo(99 - 18 - 4 - 36 - 4);
        assertThat(PixBrCode.parse(PixBrCode.of(key, null, "A", "B", null, false, "x".repeat(room))).info()).hasSize(room);
        assertThatThrownBy(() -> PixBrCode.of(key, null, "A", "B", null, false, "x".repeat(room + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String withCrc(String payload) {
        return payload + "6304" + PixBrCode.crc16(payload + "6304");
    }
}
