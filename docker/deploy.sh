#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "========================================="
echo "  The Dehydrator - One-Click Deploy"
echo "========================================="

# Check docker
if ! command -v docker &> /dev/null; then
    echo "[ERROR] Docker is not installed."
    exit 1
fi

if ! docker compose version &> /dev/null; then
    echo "[ERROR] Docker Compose is not available."
    exit 1
fi

echo "[1/3] Building images..."
docker compose build

echo "[2/3] Starting services..."
docker compose up -d

echo "[3/3] Waiting for services to be ready..."
echo ""

# Wait for business-service health
echo -n "Waiting for Business Service"
for i in $(seq 1 60); do
    if curl -sf http://localhost:8080/api/documents > /dev/null 2>&1; then
        echo " [OK]"
        break
    fi
    echo -n "."
    sleep 3
done

echo ""
echo "========================================="
echo "  Deployment Complete!"
echo "========================================="
echo ""
echo "  Web UI:        http://localhost"
echo "  API:           http://localhost:8080"
echo "  RabbitMQ:      http://localhost:15672  (admin/admin)"
echo "  MinIO Console: http://localhost:9001   (admin/12345678)"
echo ""
echo "  Logs:  cd docker && docker compose logs -f"
echo "  Stop:  cd docker && docker compose down"
echo ""
