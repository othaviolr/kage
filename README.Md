# 影 KAGE

Sistema bancário digital completo construído com Java e Spring Boot.

## O que é

KAGE 影 - é um projeto de portfólio que simula um banco digital moderno, do tipo Nubank. Foi desenvolvido pra demonstrar domínio de arquitetura enterprise, DDD e padrões de projeto usados em fintechs de verdade.

## Funcionalidades

### Pronto

- **Clientes**: cadastro com validação de CPF, e-mail, telefone e maioridade; ciclo de KYC (aprovar/rejeitar); bloqueio e desbloqueio
- **Contas**: corrente e poupança, número sequencial com dígito verificador módulo 11, depósito, saque, bloqueio, encerramento
- **Limites acumulados**: saque diário, PIX diário e PIX noturno (20h–6h), somando o que já foi movimentado no período
- **Extrato**: todo movimento vira um lançamento imutável com o saldo resultante (`GET /api/accounts/{id}/statement`)
- **PIX**: chaves, envio assíncrono com confirmação ou falha, estorno que devolve o dinheiro (ou falha com motivo, se quem recebeu já gastou)
- **Cartão de crédito**: emissão vinculada à conta, limite, compras idempotentes, fatura mensal com fechamento e pagamento pelo saldo da conta (débito, extrato e liberação do limite numa única transação)

- **Segurança**: cadastro pelo app com senha (BCrypt), login por CPF devolvendo JWT de 15 min, travamento após 5 senhas erradas, cliente só acessa o que é dele, backoffice com papel ADMIN

### Roadmap

- Refresh token e revogação (logout), 2FA no login de dispositivo novo
- PIX agendado
- Pagamento parcial/mínimo de fatura (rotativo), juros por atraso e parcelamento no cartão
- TED, boletos, empréstimos, investimentos
- Notificações (push, e-mail, SMS)

## Stack

- **Backend**: Java 17, Spring Boot 4.0.3
- **Banco**: PostgreSQL 15
- **Mensageria**: RabbitMQ 3
- **Migrations**: Flyway
- **Segurança**: Spring Security + JWT (jjwt, HS256) + BCrypt
- **Docs**: SpringDoc OpenAPI (Swagger)
- **Testes**: JUnit 5, Mockito, Testcontainers
- **Infra**: Docker Compose

## Arquitetura

Clean Architecture + DDD com comunicação event-driven entre bounded contexts. Monolito modular preparado pra evoluir pra microserviços se necessário.

Contextos: `customer`, `account`, `payment` (PIX) e `card`. Cada um tem `domain` (entidades, value objects, portas), `application` (use cases) e `infrastructure` (JPA, RabbitMQ, controllers). Um contexto só conversa com outro por portas (interfaces no domínio de quem pede) ou por eventos.

### Decisões que garantem que o dinheiro fecha

- **Transactional Outbox**: eventos são gravados na tabela `outbox_events` na mesma transação da mudança de saldo, e um relay entrega no RabbitMQ. Não existe saldo alterado sem evento, nem evento de algo que sofreu rollback.
- **Inbox (idempotência)**: todo consumer registra o id do evento antes de processar; reentrega do RabbitMQ não debita duas vezes.
- **Falha é um resultado, não uma exceção**: se o débito de um PIX ou estorno viola regra de negócio, o Account responde `*.failed` com o motivo, em vez de deixar a mensagem cair na DLQ e a operação ficar presa em processamento.
- **Concorrência**: locking otimista (`@Version`) em conta, cartão e fatura; lock pessimista na conta de origem durante o envio de PIX, para dois envios simultâneos não furarem o limite.
- **Ledger**: o extrato é append-only e guarda o saldo após cada lançamento.

### Fluxo do PIX

```
SendPix ──outbox──> pix.sent ──> Account debita/credita ──outbox──> pix.debit.confirmed ──> COMPLETED
                                          └─ regra violada ─────────> pix.debit.failed   ──> FAILED

ApprovePixRefund ──outbox──> pix.refund.approved ──> Account devolve ──> pix.refund.completed | pix.refund.failed
```

## Como rodar
```bash
# Sobe PostgreSQL e RabbitMQ
docker-compose up -d

# Roda a aplicação (IntelliJ ou mvn)
mvn spring-boot:run
```

### Autenticação

Todas as rotas exigem `Authorization: Bearer <token>`, menos cadastro e login.

```bash
# cliente: cadastro pelo app e login por CPF
POST /api/auth/signup   { ...dados do cliente..., "password": "Sombra-2026" }
POST /api/auth/login    { "cpf": "52998224725", "password": "Sombra-2026" }

# backoffice (KYC, bloqueios, estornos, emissão de cartão) e simulação de sistemas externos (depósito, compra no cartão)
POST /api/auth/admin/login  { "username": "admin", "password": "kage-admin-dev-123" }
```

| Quem | Pode |
|---|---|
| Cliente | ver e mudar os próprios dados, abrir conta, sacar, extrato, limites, chaves e envio de PIX, pedir estorno do que pagou, ver/bloquear/cancelar os próprios cartões e faturas |
| ADMIN | tudo, incluindo KYC, bloqueio de cliente/conta, aprovar/rejeitar estorno, emitir cartão (o limite é decisão de crédito), depósito e compra no cartão |

Recurso de outro cliente responde **404** (e não 403), para não confirmar que o id existe. Em produção, defina `KAGE_JWT_SECRET` (≥ 32 bytes aleatórios) e `KAGE_ADMIN_PASSWORD`; os valores padrão do `application.yml` são só para desenvolvimento.

A coleção do Postman (`docs/postman`) já faz o login: a pasta 0 pega o token de admin, e a pasta 10 testa o acesso do cliente.

Acesse:
- API: http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui.html
- RabbitMQ Management: http://localhost:15672 (user: kage / pass: kage_shadow_2024)

## Por que "Kage"?

Nasceu do meu interesse por bancos e fintechs. Conforme fui estudando arquitetura de sistemas financeiros, percebi que a melhor forma de realmente entender esse universo seria construir meu próprio banco digital do zero. O que começou como um projeto de estudo foi crescendo. Contas, cartões, Pix, pagamentos, limites, transações, validações e toda a complexidade que existe por trás de uma simples operação passaram a fazer parte do KAGE. Durante meses, o KAGE foi meu laboratório: um espaço para testar ideias, tomar decisões de arquitetura e transformar conhecimento em algo real.

---

*Built in the shadows. 影*
