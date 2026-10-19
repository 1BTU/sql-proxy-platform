FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN useradd --system --uid 10001 --create-home sqlproxy \
    && mkdir -p /app/data /app/config \
    && chown -R sqlproxy:sqlproxy /app

COPY --chown=sqlproxy:sqlproxy target/dm-database-api-1.0.0.jar /app/app.jar

USER sqlproxy
ENV TZ=Asia/Shanghai \
    APP_DATA_DIR=/app/data \
    SPRING_PROFILES_ACTIVE=prod \
    SPRING_CONFIG_ADDITIONAL_LOCATION=optional:file:/app/config/
EXPOSE 3000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
