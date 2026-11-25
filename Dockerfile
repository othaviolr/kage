# syntax=docker/dockerfile:1

# ---- build: compila e empacota (os testes rodam na CI, não aqui) ----
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace

# dependências primeiro: mudar código não invalida a camada do download do Maven
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests && cp target/*.jar app.jar

# ---- runtime: só o JRE e o jar ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# não roda como root
RUN addgroup -S kage && adduser -S kage -G kage
COPY --from=build --chown=kage:kage /workspace/app.jar app.jar
USER kage

EXPOSE 8080
# o container respeita o limite de memória dele (e não o da máquina)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
