# 影 KAGE

Sistema bancário digital completo construído com Java e Spring Boot.

## O que é

KAGE 影 - é um projeto de portfólio que simula um banco digital moderno, do tipo Nubank. Foi desenvolvido pra demonstrar domínio de arquitetura enterprise, DDD e padrões de projeto usados em fintechs de verdade.

## Funcionalidades

- Cadastro de clientes com KYC
- Contas corrente e poupança
- PIX completo (chaves, transferências, agendamento, estornos)
- TED/DOC
- Boletos
- Cartão de crédito (faturas, parcelamentos)
- Empréstimos
- Investimentos (CDB, fundos)
- Extrato e histórico de transações
- Notificações (push, email, SMS)

## Stack

- **Backend**: Java 17, Spring Boot 4.0.3
- **Banco**: PostgreSQL 15
- **Mensageria**: RabbitMQ 3
- **Migrations**: Flyway
- **Segurança**: Spring Security + JWT
- **Docs**: SpringDoc OpenAPI (Swagger)
- **Testes**: JUnit 5, Mockito, Testcontainers
- **Infra**: Docker Compose

## Arquitetura

Clean Architecture + DDD com comunicação event-driven entre bounded contexts. Monolito modular preparado pra evoluir pra microserviços se necessário.

## Como rodar
```bash
# Sobe PostgreSQL e RabbitMQ
docker-compose up -d

# Roda a aplicação (IntelliJ ou mvn)
mvn spring-boot:run
```

Acesse:
- API: http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui.html
- RabbitMQ Management: http://localhost:15672 (user: kage / pass: kage_shadow_2024)

## Por que "Kage"?

Nasceu do meu interesse por bancos e fintechs. Conforme fui estudando arquitetura de sistemas financeiros, percebi que a melhor forma de realmente entender esse universo seria construir meu próprio banco digital do zero. O que começou como um projeto de estudo foi crescendo. Contas, cartões, Pix, pagamentos, limites, transações, validações e toda a complexidade que existe por trás de uma simples operação passaram a fazer parte do KAGE. Durante meses, o KAGE foi meu laboratório: um espaço para testar ideias, tomar decisões de arquitetura e transformar conhecimento em algo real.

---

*Built in the shadows. 影*
