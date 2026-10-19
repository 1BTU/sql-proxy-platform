#!/bin/bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
IMAGE_NAME="${IMAGE_NAME:-sql-proxy-platform:v1.1.12}"
TAR_NAME="${TAR_NAME:-sql-proxy-platform.tar}"
OUTPUT_DIR="${OUTPUT_DIR:-${SCRIPT_DIR}/release}"
FRONTEND_DIR="${SCRIPT_DIR}/frontend"
STATIC_DIR="${SCRIPT_DIR}/src/main/resources/static"

echo "=== 1. 构建 React 管理端 ==="
cd "${FRONTEND_DIR}"
npm ci
npm run build

echo "=== 2. 合入后端静态资源 ==="
mkdir -p "${STATIC_DIR}"
rm -rf "${STATIC_DIR:?}/"*
cp -r "${FRONTEND_DIR}/dist/." "${STATIC_DIR}/"

echo "=== 3. 运行测试并打包 Spring Boot ==="
cd "${SCRIPT_DIR}"
mvn -s settings.xml clean test package

echo "=== 4. 构建 linux/amd64 镜像 ==="
DOCKER_DEFAULT_PLATFORM=linux/amd64 docker build --pull=false -t "${IMAGE_NAME}" "${SCRIPT_DIR}"

echo "=== 5. 导出离线部署包 ==="
mkdir -p "${OUTPUT_DIR}/config"
docker save -o "${OUTPUT_DIR}/${TAR_NAME}" "${IMAGE_NAME}"
cp "${SCRIPT_DIR}/deploy.sh" "${OUTPUT_DIR}/deploy.sh"
cp "${SCRIPT_DIR}/src/main/resources/application-prod.yml" "${OUTPUT_DIR}/config/application-prod.yml"
chmod +x "${OUTPUT_DIR}/deploy.sh"

echo "================================================================================"
echo "构建完成"
echo "镜像文件: ${OUTPUT_DIR}/${TAR_NAME}"
echo "部署脚本: ${OUTPUT_DIR}/deploy.sh"
echo "将 release 目录上传到 x86_64 Linux 服务器后执行: bash deploy.sh"
echo "================================================================================"
