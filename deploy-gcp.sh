#!/usr/bin/env bash
# =====================================================================
# Despliegue turnkey en GCP (Opción A: VM única + Docker Compose).
#
# Requisitos previos en tu máquina:
#   - gcloud CLI autenticado:  gcloud auth login
#   - Docker en marcha (para build/push de las imágenes)
#   - Archivo .env relleno:     cp .env.example .env  &&  editar valores
#
# Uso:
#   ./deploy-gcp.sh            # build + push + crea VM + arranca el stack
#   ./deploy-gcp.sh images     # solo construye y sube las imágenes
#   ./deploy-gcp.sh infra      # solo crea VM + firewall
#   ./deploy-gcp.sh up         # solo copia compose y arranca el stack
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")"

# ---- Cargar configuración ----
if [[ ! -f .env ]]; then
  echo "ERROR: no existe .env. Ejecuta:  cp .env.example .env  y rellena los valores." >&2
  exit 1
fi
set -a; source .env; set +a

: "${PROJECT_ID:?Define PROJECT_ID en .env}"
: "${REGION:?Define REGION en .env}"
: "${REPO:?Define REPO en .env}"
: "${ZONE:?Define ZONE en .env}"
: "${VM_NAME:?Define VM_NAME en .env}"
: "${MACHINE_TYPE:?Define MACHINE_TYPE en .env}"
: "${AGENT_SOURCE_RANGES:?Define AGENT_SOURCE_RANGES en .env}"

REGISTRY="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO}"
SERVER_IMAGE="${REGISTRY}/rmi-server:latest"
CLIENT_IMAGE="${REGISTRY}/rmi-client:latest"

gcloud config set project "${PROJECT_ID}" >/dev/null

# ---------------------------------------------------------------------
build_and_push_images() {
  echo "==> Habilitando APIs necesarias..."
  gcloud services enable artifactregistry.googleapis.com compute.googleapis.com

  echo "==> Asegurando repositorio de Artifact Registry '${REPO}'..."
  gcloud artifacts repositories describe "${REPO}" --location="${REGION}" >/dev/null 2>&1 || \
    gcloud artifacts repositories create "${REPO}" \
      --repository-format=docker --location="${REGION}" \
      --description="Repositorio Docker para Clúster RMI y Cliente"

  echo "==> Configurando auth de Docker para ${REGION}-docker.pkg.dev..."
  gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

  echo "==> Construyendo y subiendo imagen del servidor..."
  docker build -t "${SERVER_IMAGE}" -f server/Dockerfile .
  docker push "${SERVER_IMAGE}"

  echo "==> Construyendo y subiendo imagen del cliente..."
  docker build -t "${CLIENT_IMAGE}" -f client/Dockerfile .
  docker push "${CLIENT_IMAGE}"
}

# ---------------------------------------------------------------------
create_infra() {
  echo "==> Asegurando VM '${VM_NAME}'..."
  gcloud compute instances describe "${VM_NAME}" --zone="${ZONE}" >/dev/null 2>&1 || \
    gcloud compute instances create "${VM_NAME}" \
      --zone="${ZONE}" \
      --machine-type="${MACHINE_TYPE}" \
      --image-family=ubuntu-2204-lts \
      --image-project=ubuntu-os-cloud \
      --boot-disk-size=20GB \
      --tags=rmi-cluster-node

  echo "==> Asegurando regla de firewall para Kafka externo (9094)..."
  gcloud compute firewall-rules describe allow-kafka-external >/dev/null 2>&1 || \
    gcloud compute firewall-rules create allow-kafka-external \
      --allow=tcp:9094 \
      --source-ranges="${AGENT_SOURCE_RANGES}" \
      --target-tags=rmi-cluster-node \
      --description="Entrada a Kafka 9094 desde los AgenteGo autorizados"

  echo "==> Instalando Docker en la VM (si falta)..."
  gcloud compute ssh "${VM_NAME}" --zone="${ZONE}" --command "\
    command -v docker >/dev/null 2>&1 || { \
      sudo apt-get update && \
      sudo apt-get install -y docker.io docker-compose-v2 && \
      sudo systemctl enable --now docker && \
      sudo usermod -aG docker \$USER; }"
}

# ---------------------------------------------------------------------
start_stack() {
  echo "==> Resolviendo IP pública de la VM..."
  EXTERNAL_HOST="$(gcloud compute instances describe "${VM_NAME}" --zone="${ZONE}" \
    --format='get(networkInterfaces[0].accessConfigs[0].natIP)')"
  echo "    EXTERNAL_HOST=${EXTERNAL_HOST}"

  # Persistir la IP en .env para futuros 'up'
  if grep -q '^EXTERNAL_HOST=' .env; then
    sed -i.bak "s|^EXTERNAL_HOST=.*|EXTERNAL_HOST=${EXTERNAL_HOST}|" .env && rm -f .env.bak
  fi

  echo "==> Generando .env remoto para la VM..."
  REMOTE_ENV="$(mktemp)"
  cat > "${REMOTE_ENV}" <<EOF
PROJECT_ID=${PROJECT_ID}
REGION=${REGION}
REPO=${REPO}
EXTERNAL_HOST=${EXTERNAL_HOST}
EOF

  echo "==> Copiando compose y .env a la VM..."
  gcloud compute scp docker-compose.gcp.yml "${VM_NAME}":~/docker-compose.yml --zone="${ZONE}"
  gcloud compute scp "${REMOTE_ENV}" "${VM_NAME}":~/.env --zone="${ZONE}"
  rm -f "${REMOTE_ENV}"

  echo "==> Autenticando Docker y arrancando el stack en la VM..."
  gcloud compute ssh "${VM_NAME}" --zone="${ZONE}" --command "\
    gcloud auth configure-docker ${REGION}-docker.pkg.dev --quiet && \
    sudo docker compose -f ~/docker-compose.yml --env-file ~/.env pull && \
    sudo docker compose -f ~/docker-compose.yml --env-file ~/.env up -d && \
    sudo docker compose -f ~/docker-compose.yml ps"

  echo ""
  echo "======================================================================"
  echo " Stack desplegado. Los AgenteGo deben publicar en:"
  echo "     ${EXTERNAL_HOST}:9094"
  echo "   ./AgenteGo --kafka-brokers=${EXTERNAL_HOST}:9094"
  echo "======================================================================"
}

# ---------------------------------------------------------------------
case "${1:-all}" in
  images) build_and_push_images ;;
  infra)  create_infra ;;
  up)     start_stack ;;
  all)    build_and_push_images; create_infra; start_stack ;;
  *) echo "Uso: $0 [all|images|infra|up]" >&2; exit 1 ;;
esac
