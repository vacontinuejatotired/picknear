# ==============================================
# Stage 1: Build
# ==============================================
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

ENV LANG=C.UTF-8
ENV MAVEN_OPTS="-Dfile.encoding=UTF-8 -Daether.connector.http.retryHandler.count=5 -Daether.connector.http.connectionTimeout=30000 -Daether.connector.http.readTimeout=60000"

WORKDIR /build

COPY pom.xml .

# 国内 Maven 镜像配置（源自本机 D:\apache-maven-3.8.8-bin\...\conf\settings.xml）
COPY docker/maven/settings.xml /build/maven-settings.xml

# Maven 本地仓库 bind-mount 构建上下文里的 .m2（build.sh 构建前从宿主 /home/docker/.m2 拷贝进 context）：
# VM 容器网络不稳（下载依赖会被掐断），用宿主预热的 .m2 保证构建零网络依赖。
# 注意：source 相对 build context，compose 直建需自行把 .m2 放进 context。
RUN --mount=type=bind,source=.m2,target=/root/.m2,rw mvn -s /build/maven-settings.xml dependency:go-offline -B

COPY src src/
# -Dmaven.test.skip=true：连测试代码编译都跳过，构建更快
RUN --mount=type=bind,source=.m2,target=/root/.m2,rw mvn -s /build/maven-settings.xml package -Dmaven.test.skip=true -B

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
# 换 apk 镜像源为阿里云（VM 到 Alpine 官方 dl-cdn.alpinelinux.org 慢/冻结，曾卡 210s 被 cancel）；
# 版本自适应（v3.23 等），不写死。与 maven/registry 镜像源同理。
RUN VER=$(cut -d. -f1-2 /etc/alpine-release) && \
    echo "https://mirrors.aliyun.com/alpine/v${VER}/main" > /etc/apk/repositories && \
    echo "https://mirrors.aliyun.com/alpine/v${VER}/community" >> /etc/apk/repositories && \
    apk add --no-cache tzdata && \
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
