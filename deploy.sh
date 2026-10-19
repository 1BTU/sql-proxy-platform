#!/bin/bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
IMAGE_NAME="${IMAGE_NAME:-sql-proxy-platform:v1.1.12}"
TAR_NAME="${TAR_NAME:-sql-proxy-platform.tar}"
CONTAINER_NAME="${CONTAINER_NAME:-sql-proxy-platform}"
SERVER_PORT="${SERVER_PORT:-3001}"
DATA_DIR="${DATA_DIR:-${SCRIPT_DIR}/data}"
CONFIG_DIR="${CONFIG_DIR:-${SCRIPT_DIR}/config}"
SECRETS_FILE="${SCRIPT_DIR}/secrets.env"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"

has_local_image() {
    docker image inspect "${IMAGE_NAME}" >/dev/null 2>&1
}

ensure_image() {
    if [ -f "${SCRIPT_DIR}/${TAR_NAME}" ]; then
        echo ">>> 导入离线镜像 ${TAR_NAME}"
        docker load -i "${SCRIPT_DIR}/${TAR_NAME}"
    elif has_local_image; then
        echo ">>> 使用本地镜像 ${IMAGE_NAME}"
    else
        echo ">>> 拉取镜像 ${IMAGE_NAME}"
        docker pull "${IMAGE_NAME}"
    fi
}

generate_key() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 32 | tr -d '\n'
    else
        dd if=/dev/urandom bs=32 count=1 2>/dev/null | base64 | tr -d '\n'
    fi
}

ensure_secrets() {
    if [ -f "${SECRETS_FILE}" ]; then
        echo ">>> 保留现有 secrets.env（切勿丢失，否则历史密码和 Token 无法解密）"
        return
    fi
    echo ">>> 首次部署，生成平台主密钥"
    umask 077
    {
        echo "APP_ENCRYPTION_KEY=$(generate_key)"
        echo "ADMIN_INITIAL_USERNAME=${ADMIN_INITIAL_USERNAME:-admin}"
        echo "ADMIN_INITIAL_PASSWORD=${ADMIN_INITIAL_PASSWORD:-Admin@123456}"
    } > "${SECRETS_FILE}"
    chmod 600 "${SECRETS_FILE}"
}

write_compose() {
    local host_uid
    local host_gid
    host_uid=$(id -u)
    host_gid=$(id -g)
    cat > "${COMPOSE_FILE}" <<EOF
services:
  sql-proxy:
    image: ${IMAGE_NAME}
    container_name: ${CONTAINER_NAME}
    user: "${host_uid}:${host_gid}"
    env_file:
      - ${SECRETS_FILE}
    environment:
      SERVER_PORT: "3000"
      APP_DATA_DIR: "/app/data"
    ports:
      - "${SERVER_PORT}:3000"
    volumes:
      - ${DATA_DIR}:/app/data
      - ${CONFIG_DIR}:/app/config:ro
    restart: always
EOF
}

compose_up() {
    if docker compose version >/dev/null 2>&1; then
        docker compose -f "${COMPOSE_FILE}" up -d
    elif command -v docker-compose >/dev/null 2>&1; then
        docker-compose -f "${COMPOSE_FILE}" up -d
    else
        echo "错误：未找到 docker compose 或 docker-compose"
        exit 1
    fi
}

wait_for_health() {
    local url="http://127.0.0.1:${SERVER_PORT}/api/health"
    echo ">>> 等待服务健康检查 ${url}"
    for _ in $(seq 1 30); do
        if curl --fail --silent "${url}" >/dev/null; then
            echo ">>> 服务已就绪"
            return
        fi
        sleep 2
    done
    echo "错误：服务未在 60 秒内就绪，请运行 docker logs ${CONTAINER_NAME}"
    exit 1
}

mkdir -p "${DATA_DIR}" "${CONFIG_DIR}"
ensure_image
ensure_secrets
write_compose
compose_up
wait_for_health

echo "================================================================================"
echo "部署完成: http://<服务器IP>:${SERVER_PORT}"
echo "默认管理员: ${ADMIN_INITIAL_USERNAME:-admin} / ${ADMIN_INITIAL_PASSWORD:-Admin@123456}"
echo "首次登录后必须修改密码"
echo "查看日志: docker logs -f ${CONTAINER_NAME}"
echo "重要备份: ${DATA_DIR} 和 ${SECRETS_FILE} 必须一起备份"
echo "================================================================================"
