# ==============================================
# Stage 1: Build
# ==============================================
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

ENV LANG=C.UTF-8
ENV MAVEN_OPTS="-Dfile.encoding=UTF-8"

WORKDIR /build

COPY pom.xml .

# 国内 Maven 镜像配置（源自本机 D:\apache-maven-3.8.8-bin\...\conf\settings.xml）
COPY docker/maven/settings.xml /build/maven-settings.xml

# --mount=type=cache 复用 Maven 本地仓库，二次构建不再重新下载依赖
RUN --mount=type=cache,target=/root/.m2 mvn -s /build/maven-settings.xml dependency:go-offline -B 

COPY src src/
# -Dmaven.test.skip=true：连测试代码编译都跳过，构建更快
RUN --mount=type=cache,target=/root/.m2 mvn -s /build/maven-settings.xml package -Dmaven.test.skip=true -B 

# 拆分层，配合 JarLauncher 路径
RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ==============================================
# Stage 2: Runtime
# ==============================================
FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="picknear"
LABEL org.opencontainers.image.description="小众点评 — Spring Boot 后端服务"
LABEL org.opencontainers.image.version="1.0.0"

ENV TZ=Asia/Shanghai
ENV LANG=C.UTF-8
RUN apk add --no-cache tzdata && \
    ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 先建用户再 COPY --chown。避免在 COPY 完 ~200MB 依赖后执行 chown -R /app：
# 那会把每个文件属主都改一遍，overlay 只能重写成一份 ~200MB 的新层（同内容存两份）。
RUN addgroup -S -g 1000 appgroup && \
    adduser -S -u 1000 -G appgroup appuser

WORKDIR /app

# 分层复制，精准匹配 JarLauncher 路径；--chown 让文件直接归属 appuser，无需后续 chown
COPY --chown=appuser:appgroup --from=builder /build/extracted/dependencies/ ./
COPY --chown=appuser:appgroup --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --chown=appuser:appgroup --from=builder /build/extracted/spring-boot-loader/ ./
COPY --chown=appuser:appgroup --from=builder /build/extracted/application/ ./

# logs 目录：logback-spring.xml 的 LOG_PATH=./logs，工作目录是 /app，
# appuser 需要能建 /app/logs，故预创建并 chown（否则运行时无权创建日志目录）
RUN mkdir -p /app/logs && \
    chown appuser:appgroup /app/logs

USER appuser

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q --no-verbose --tries=1 --spider http://localhost:8082/actuator/health || exit 1

ENV JAVA_OPTS="-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=80.0 -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
