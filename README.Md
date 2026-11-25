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
- **PIX agendado**: para até 1 ano à frente; saldo e limites conferidos no dia (job às 06:00, depois da janela noturna), pelo mesmo caminho do PIX imediato; sem saldo no dia vira `FAILED` e o cliente é avisado; dá para listar e cancelar até a execução
- **Cartão de crédito**: emissão vinculada à conta, limite, compras idempotentes, fatura mensal com fechamento e pagamento pelo saldo da conta (débito, extrato e liberação do limite numa única transação)
- **Rotativo**: pagamento total ou parcial (um ou vários); o mínimo (15%, piso de R$ 50) aparece na fatura. O que sobra no próximo fechamento vai para a fatura seguinte como "Saldo anterior" com juros do rotativo (14% a.m.); abaixo do mínimo, também multa de 2% e mora de 1% a.m. pro rata. Encargos respeitam o teto da Lei 14.690/2023 (no máximo 100% da dívida original) e consomem limite
- **Parcelamento**: compra parcelada sem juros em até 12x (consome o limite total na hora; uma parcela por fatura, faturas futuras criadas na compra); parcelamento da fatura em 2–12x a 8,99% a.m. (Tabela Price), com simulação antes de contratar; e a regra do Bacen: rotativo não pago por dois ciclos é parcelado automaticamente em 6x. Juros do parcelamento contam no mesmo teto legal do rotativo
- **Ciclo de cobrança automático**: job diário (03:00) fecha as faturas no dia de corte e, para quem ligou o débito automático, paga no vencimento; sem saldo, a fatura fica em atraso e é tentada de novo no dia seguinte. O backoffice pode rodar o ciclo para qualquer data (`POST /api/admin/billing/run?date=`)

- **Notificações**: depósito, saque, PIX enviado/recebido/recusado, estornos, fatura fechada/paga e débito automático recusado viram avisos na caixa de entrada do cliente (`GET /api/notifications`) e são entregues por um canal externo (hoje simulado em log; a porta `NotificationChannel` aceita FCM, SES etc.). Consome os eventos que os contextos já publicam pelo outbox; reentrega não duplica aviso
- **Segurança**: cadastro pelo app com senha (BCrypt), login por CPF devolvendo JWT de 15 min + refresh token rotativo de 30 dias (reuso de token antigo derruba a sessão), logout que vale na hora, lista de sessões e "sair de todos os dispositivos", bloqueio do cliente derruba as sessões dele, troca de senha (derruba os outros dispositivos) e recuperação por link de uso único (30 min; resposta idêntica exista o CPF ou não; derruba todas as sessões), alerta de senha alterada, travamento após 5 senhas erradas (cliente e admin), cliente só acessa o que é dele, backoffice com papel ADMIN

### Roadmap

- 2FA no login de dispositivo novo, envio real do e-mail de recuperação de senha
- PIX agendado recorrente (todo mês)
- Antecipação de parcelas (com desconto dos juros) e compra parcelada com juros do emissor
- TED, boletos, empréstimos, investimentos
- Entrega real das notificações (push via FCM, e-mail, SMS) e preferências de canal por cliente

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

Contextos: `customer`, `account`, `payment` (PIX), `card`, `auth` e `notification`. Cada um tem `domain` (entidades, value objects, portas), `application` (use cases) e `infrastructure` (JPA, RabbitMQ, controllers). Um contexto só conversa com outro por portas (interfaces no domínio de quem pede) ou por eventos.

### Decisões que garantem que o dinheiro fecha

- **Transactional Outbox**: eventos são gravados na tabela `outbox_events` na mesma transação da mudança de saldo, e um relay entrega no RabbitMQ. Não existe saldo alterado sem evento, nem evento de algo que sofreu rollback.
- **Inbox (idempotência)**: todo consumer registra o id do evento antes de processar; reentrega do RabbitMQ não debita duas vezes.
- **Falha é um resultado, não uma exceção**: se o débito de um PIX ou estorno viola regra de negócio, o Account responde `*.failed` com o motivo, em vez de deixar a mensagem cair na DLQ e a operação ficar presa em processamento.
- **Concorrência**: locking otimista (`@Version`) em conta, cartão e fatura; lock pessimista na conta de origem durante o envio de PIX, para dois envios simultâneos não furarem o limite.
- **Ledger**: o extrato é append-only e guarda o saldo após cada lançamento.
- **Idempotency-Key** (modelo Stripe): nas rotas que movem dinheiro (PIX, saque, depósito, compra, pagamento e parcelamento de fatura, estorno), o header `Idempotency-Key` garante que um retry de rede não execute duas vezes — o reenvio devolve a resposta guardada com `Idempotent-Replayed: true`. Chave por usuário, válida por 24h; mesma chave com outro corpo → 422; em andamento → 409 (na dúvida, nunca reexecuta).

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
POST /api/auth/login    { "cpf": "52998224725", "password": "Sombra-2026" }   # -> accessToken + refreshToken
POST /api/auth/refresh  { "refreshToken": "..." }   # par novo; o refresh antigo deixa de valer
POST /api/auth/logout                               # encerra esta sessão (o token para de valer na hora)
GET  /api/auth/sessions | DELETE /api/auth/sessions/{id} | POST /api/auth/logout-all
POST /api/auth/password         { "currentPassword": "...", "newPassword": "..." }
POST /api/auth/password/forgot  { "cpf": "..." }                  # sempre 202
POST /api/auth/password/reset   { "token": "...", "newPassword": "..." }

# backoffice (KYC, bloqueios, estornos, emissão de cartão) e simulação de sistemas externos (depósito, compra no cartão)
POST /api/auth/admin/login  { "username": "admin", "password": "kage-admin-dev-123" }
```

| Quem | Pode |
|---|---|
| Cliente | ver e mudar os próprios dados, abrir conta, sacar, extrato, limites, chaves e envio de PIX, pedir estorno do que pagou, ver/bloquear/cancelar os próprios cartões e faturas |
| ADMIN | tudo, incluindo KYC, bloqueio de cliente/conta, aprovar/rejeitar estorno, emitir cartão (o limite é decisão de crédito), depósito, compra no cartão e rodar o ciclo de cobrança |

Recurso de outro cliente responde **404** (e não 403), para não confirmar que o id existe. Em produção, defina `KAGE_JWT_SECRET` (≥ 32 bytes aleatórios) e `KAGE_ADMIN_PASSWORD`; os valores padrão do `application.yml` são só para desenvolvimento.

Não há envio de e-mail real: o link de recuperação de senha é "enviado" por um canal simulado que **não** registra o token. Para testar o fluxo completo localmente, suba com `KAGE_DEV_EXPOSE_RESET_TOKEN=true` — o token volta na resposta do `/password/forgot`. **Nunca ligue isso em produção.**

A coleção do Postman (`docs/postman`) já faz o login: a pasta 0 pega o token de admin, e a pasta 10 testa o acesso do cliente.

Acesse:
- API: http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui.html
- RabbitMQ Management: http://localhost:15672 (user: kage / pass: kage_shadow_2024)

## Por que "Kage"?

Nasceu do meu interesse por bancos e fintechs. Conforme fui estudando arquitetura de sistemas financeiros, percebi que a melhor forma de realmente entender esse universo seria construir meu próprio banco digital do zero. O que começou como um projeto de estudo foi crescendo. Contas, cartões, Pix, pagamentos, limites, transações, validações e toda a complexidade que existe por trás de uma simples operação passaram a fazer parte do KAGE. Durante meses, o KAGE foi meu laboratório: um espaço para testar ideias, tomar decisões de arquitetura e transformar conhecimento em algo real.

---

*Built in the shadows. 影*
