# syntax=docker/dockerfile:1

# ---- build: compila e empacota (os testes rodam na CI, não aqui) ----
# o Maven já vem na imagem: o build não baixa a distribuição do Maven Central
# (o mvnw baixava a cada build sem cache, e um 429 do Central derrubava a CI)
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /workspace

# dependências primeiro: mudar código não invalida esta camada (a CI guarda ela no cache)
COPY pom.xml ./
RUN mvn -B -q -ntp dependency:go-offline

COPY src/ src/
RUN mvn -B -q -ntp -o package -DskipTests && cp target/*.jar app.jar

# ---- runtime: só o JRE e o jar ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# não roda como root
RUN addgroup -S kage && adduser -S kage -G kage
COPY --from=build --chown=kage:kage /workspace/app.jar app.jar
USER kage

EXPOSE 8080
# o container respeita o limite de memória dele (e não o da máquina)
# fuso de Brasília: o "dia" do limite diário, a janela noturna do PIX (20h–6h) e o "hoje" dos
# agendamentos são horários locais. Sem isso a JVM roda em UTC e o dia vira às 21h de Brasília
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=America/Sao_Paulo"

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
