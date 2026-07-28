# ==============================================
# Stage 1: Build
# ==============================================
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

ENV LANG=C.UTF-8
ENV MAVEN_OPTS="-Dfile.encoding=UTF-8"

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B -q

COPY src src/
RUN mvn package -DskipTests -B -q

# 拆分层，配合 JarLauncher 路径
RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ==============================================
# Stage 2: Runtime
# ==============================================
FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="hm-dianping"
LABEL org.opencontainers.image.description="小众点评 — Spring Boot 后端服务"
LABEL org.opencontainers.image.version="1.0.0"

ENV TZ=Asia/Shanghai
ENV LANG=C.UTF-8
RUN apk add --no-cache tzdata && \
    ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app

# 分层复制，精准匹配 JarLauncher 路径
COPY --from=builder /build/extracted/dependencies/ ./BOOT-INF/lib/
COPY --from=builder /build/extracted/snapshot-dependencies/ ./BOOT-INF/lib/
COPY --from=builder /build/extracted/spring-boot-loader/ ./
COPY --from=builder /build/extracted/application/ ./BOOT-INF/classes/

RUN mkdir -p /app/config
VOLUME /app/config

RUN addgroup -S -g 1000 appgroup && \
    adduser -S -u 1000 -G appgroup appuser && \
    chown -R appuser:appgroup /app
USER appuser

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q --no-verbose --tries=1 --spider http://localhost:8082/actuator/health || exit 1

ENV JAVA_OPTS="-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=80.0 -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.JarLauncher --spring.config.additional-location=optional:file:/app/config/"]
