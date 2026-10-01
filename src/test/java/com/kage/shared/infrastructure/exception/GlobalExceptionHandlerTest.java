package com.kage.shared.infrastructure.exception;

import com.kage.shared.domain.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Garante que as falhas de contrato HTTP (parâmetro ausente, tipo errado, JSON ruim, rota ou método
 * inexistente) viram 4xx no formato de ErrorResponse, em vez de cair no handleGeneric como 500.
 *
 * Usa MockMvc standalone com um controller fake mínimo: sem contexto Spring, sem banco, sem RabbitMQ.
 * O controller fake precisa de @RestController (desde o Spring 6, @RequestMapping sozinho na classe não
 * a torna um handler) e de @TestComponent, que o Boot exclui do component scan, pra ele não vazar
 * pros testes de integração.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FakeController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void deveRetornar400_quandoParametroObrigatorioAusente() throws Exception {
        mockMvc.perform(get("/fake/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Parâmetro obrigatório ausente: processedBy"))
                .andExpect(jsonPath("$.path").value("/fake/param"));
    }

    @Test
    void deveRetornar400_quandoParametroDeCaminhoNaoEhUuid() throws Exception {
        mockMvc.perform(get("/fake/uuid/nao-e-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Valor inválido para o parâmetro 'id'"));
    }

    @Test
    void deveRetornar400_quandoJsonMalformado() throws Exception {
        mockMvc.perform(post("/fake/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ isso nao eh json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Corpo da requisição ausente ou malformado"));
    }

    @Test
    void deveRetornar400_quandoCorpoAusente() throws Exception {
        mockMvc.perform(post("/fake/body").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Corpo da requisição ausente ou malformado"));
    }

    @Test
    void deveRetornar400_quandoValorNaoExisteNoEnum() throws Exception {
        mockMvc.perform(post("/fake/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyType\":\"INEXISTENTE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Corpo da requisição ausente ou malformado"));
    }

    @Test
    void deveRetornar405_quandoMetodoNaoSuportado() throws Exception {
        mockMvc.perform(get("/fake/only-post"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("Método GET não suportado para este recurso"));
    }

    @Test
    void deveRetornar404_quandoRecursoNaoEncontrado() {
        // NoResourceFoundException só nasce do ResourceHttpRequestHandler do app real, que o MockMvc
        // standalone não registra; por isso o handler é chamado direto, com a exceção mockada.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rota-que-nao-existe");

        ResponseEntity<ErrorResponse> response =
                handler.handleNoResourceFound(mock(NoResourceFoundException.class), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().message()).isEqualTo("Recurso não encontrado");
        assertThat(response.getBody().path()).isEqualTo("/api/rota-que-nao-existe");
    }

    @Test
    void deveRetornar409_quandoViolacaoDeIntegridadeDeDados() throws Exception {
        mockMvc.perform(get("/fake/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("O registro já existe ou conflita com dados existentes."));
    }

    @Test
    void deveRetornar409_quandoConflictException() throws Exception {
        mockMvc.perform(get("/fake/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Compra já lançada"));
    }

    @Test
    void deveContinuarRetornando500_quandoErroInesperado() throws Exception {
        mockMvc.perform(get("/fake/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("Erro interno inesperado"));
    }

    @TestComponent
    @RestController
    @RequestMapping("/fake")
    static class FakeController {

        enum KeyType { CPF, EMAIL, PHONE, RANDOM }

        record Payload(KeyType keyType) {}

        @GetMapping("/param")
        void param(@RequestParam String processedBy) {
        }

        @GetMapping("/uuid/{id}")
        void uuid(@PathVariable UUID id) {
        }

        @PostMapping("/body")
        void body(@RequestBody Payload payload) {
        }

        @PostMapping("/only-post")
        void onlyPost() {
        }

        @GetMapping("/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("uq_invoice_items_purchase");
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ConflictException("Compra já lançada");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("erro qualquer");
        }
    }
}
