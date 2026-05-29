# 🚀 Guía de Despliegue en Google Cloud Platform (GCP)

Esta guía detalla cómo desplegar el clúster RMI de servidores (`ClusterNode`), el cliente consumidor de Kafka (`Client`) y el broker de Kafka en GCP. Todo el despliegue de la **Opción A está automatizado** con `deploy-gcp.sh`; la Opción B (GKE) usa el manifiesto `k8s-deployment.yaml`.

---

## 🏗️ Resumen de Arquitectura en la Nube

```
[AgenteGo en máquinas externas]  --Avro/Kafka 9094-->  [ Kafka ]
                                                           │ (topic "telemetry", listener INTERNAL 9092)
                                                           ▼
                                                       [ Client ]  --RMI round-robin + failover-->  [ node-1 | node-2 | node-3 ]
```

- **AgenteGo NO se despliega en cloud**: corre en cada máquina monitorizada y solo necesita alcanzar el puerto **9094** de Kafka.
- **Kafka + Client + 3 nodos** sí van a cloud, en la misma red para que RMI resuelva los hostnames `node-1/2/3`.

Dos enfoques:

1. **Opción A (recomendada): Google Compute Engine (GCE)** — una VM con Docker Compose.
   - **Por qué**: RMI embute el hostname en el stub y exporta el objeto remoto en un **puerto anónimo** (`super(0)` en `ClusterNode`). Dentro de la red bridge de Compose esto "just works" sin abrir puertos extra. Ideal para demo de tolerancia a fallos y pre-producción.
2. **Opción B: Google Kubernetes Engine (GKE)** — `StatefulSet` con DNS headless estable (`rmi-node-0.rmi-service`). Solo merece la pena si necesitas escalado elástico.

> ⚠️ **Kafka es un único broker (SPOF).** El clúster RMI es altamente disponible (puedes matar nodos y sigue funcionando), pero Kafka no. Para producción real usa Confluent Cloud o un clúster Kafka multi-broker. Para demo/pre-producción, un solo broker es suficiente.

---

## ✅ Opción A — Despliegue automatizado (turnkey)

### Requisitos previos (en tu máquina local)
- `gcloud` CLI autenticado: `gcloud auth login`
- Docker en marcha (para construir y subir las imágenes)

### Paso 1: Configurar variables
```bash
cp .env.example .env
# Edita .env y rellena al menos PROJECT_ID, REGION, ZONE.
# Deja EXTERNAL_HOST vacío: el script lo rellena tras crear la VM.
# IMPORTANTE: ajusta AGENT_SOURCE_RANGES a las IPs de tus AgenteGo (NO 0.0.0.0/0).
```

### Paso 2: Desplegar todo de una vez
```bash
./deploy-gcp.sh
```

El script ejecuta, de forma idempotente:
1. Habilita las APIs (`artifactregistry`, `compute`).
2. Crea el repositorio de Artifact Registry si no existe.
3. Construye y sube las imágenes `rmi-server` y `rmi-client`.
4. Crea la VM (`e2-medium`, Ubuntu 22.04) con la etiqueta `rmi-cluster-node`.
5. Crea la regla de firewall para el puerto **9094** restringida a `AGENT_SOURCE_RANGES`.
6. Instala Docker en la VM.
7. Resuelve la **IP pública** de la VM, la inyecta como `EXTERNAL_HOST` y arranca el stack con `docker-compose.gcp.yml`.

Al terminar, imprime la dirección que deben usar los AgenteGo (`<IP_PUBLICA>:9094`).

### Pasos parciales (opcional)
```bash
./deploy-gcp.sh images   # solo build + push de imágenes
./deploy-gcp.sh infra    # solo VM + firewall + Docker
./deploy-gcp.sh up        # solo copiar compose y (re)arrancar el stack
```

### Conectar los AgenteGo
En cada máquina monitorizada:
```bash
./AgenteGo --kafka-brokers=<IP_PUBLICA_VM>:9094
```

---

## 🛠️ Opción A — Pasos manuales (referencia)

Si prefieres no usar el script, estos son los comandos equivalentes.

### 1. Artifact Registry e imágenes
```bash
gcloud services enable artifactregistry.googleapis.com compute.googleapis.com

gcloud artifacts repositories create rmi-cluster-repo \
    --repository-format=docker --location=europe-west1 \
    --description="Repositorio Docker para Clúster RMI y Cliente"

gcloud auth configure-docker europe-west1-docker.pkg.dev

# Sustituye [PROJECT_ID]
docker build -t europe-west1-docker.pkg.dev/[PROJECT_ID]/rmi-cluster-repo/rmi-server:latest -f server/Dockerfile .
docker push europe-west1-docker.pkg.dev/[PROJECT_ID]/rmi-cluster-repo/rmi-server:latest

docker build -t europe-west1-docker.pkg.dev/[PROJECT_ID]/rmi-cluster-repo/rmi-client:latest -f client/Dockerfile .
docker push europe-west1-docker.pkg.dev/[PROJECT_ID]/rmi-cluster-repo/rmi-client:latest
```

### 2. Crear la VM
```bash
gcloud compute instances create rmi-cluster-vm \
    --zone=europe-west1-b \
    --machine-type=e2-medium \
    --image-family=ubuntu-2204-lts \
    --image-project=ubuntu-os-cloud \
    --boot-disk-size=20GB \
    --tags=rmi-cluster-node
```

### 3. Firewall para Kafka externo (puerto 9094)
> ⚠️ Restringe SIEMPRE el origen. Kafka va en PLAINTEXT; abrirlo a `0.0.0.0/0` deja el broker accesible a todo Internet.
```bash
gcloud compute firewall-rules create allow-kafka-external \
    --allow=tcp:9094 \
    --source-ranges=203.0.113.10/32 \
    --target-tags=rmi-cluster-node \
    --description="Entrada a Kafka 9094 desde los AgenteGo autorizados"
```

### 4. Instalar Docker, copiar compose y arrancar
```bash
gcloud compute ssh rmi-cluster-vm --zone=europe-west1-b
# Dentro de la VM:
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2
sudo systemctl enable --now docker
gcloud auth configure-docker europe-west1-docker.pkg.dev
```
Sube `docker-compose.gcp.yml` (renómbralo a `docker-compose.yml` en la VM) y un `.env` con `PROJECT_ID`, `REGION`, `REPO` y `EXTERNAL_HOST=<IP_PUBLICA_VM>`, luego:
```bash
sudo docker compose --env-file .env pull
sudo docker compose --env-file .env up -d
```

> 🔑 **Por qué `EXTERNAL_HOST` es obligatorio en cloud:** si el listener externo anuncia `localhost:9094`, los AgenteGo hacen bootstrap contra la VM pero Kafka les responde "reconéctate a localhost" → se conectan a su propia máquina y fallan. Debe ser la IP/DNS pública de la VM.

---

## ☸️ Opción B: Despliegue en Google Kubernetes Engine (GKE)

El manifiesto completo y funcional está en **`k8s-deployment.yaml`** e incluye Kafka, el `StatefulSet` de los 3 nodos (con DNS headless) y el `Deployment` del Client.

```bash
# 1. Sustituye los placeholders por tus valores
sed -i 's/\[PROJECT_ID\]/mi-proyecto/; s/\[REGION\]/europe-west1/' k8s-deployment.yaml

# 2. Autentica kubectl contra tu clúster GKE y aplica
kubectl apply -f k8s-deployment.yaml
```

Kubernetes arranca `rmi-node-0/1/2` de forma ordenada; cada uno se comunica con los demás vía el DNS headless `rmi-node-X.rmi-service`. El Client consume de `kafka:9092` y reparte la telemetría por RMI.

> Para que los AgenteGo externos publiquen en este Kafka necesitarías exponerlo (Service `LoadBalancer` + listener EXTERNAL con la IP del LB). El manifiesto base solo expone Kafka internamente; añade ese listener si tus agentes están fuera del clúster.

---

## 🔐 Configuración de TLS / SSL en Contenedores

Si ejecutas los nodos con seguridad SSL activada (ver `RUN_WITH_TLS.md`):

1. **GCE / Compose**:
   - Genera `server.keystore` con `generate-certs.sh`.
   - Móntalo como volumen de solo lectura en cada servicio de `docker-compose.gcp.yml`:
     ```yaml
     volumes:
       - ./server.keystore:/app/server.keystore:ro
     ```
   - Inyecta las propiedades JVM:
     ```yaml
     environment:
       - JVM_OPTS=-Djava.rmi.server.hostname=node-1 -Djavax.net.ssl.keyStore=/app/server.keystore -Djavax.net.ssl.keyStorePassword=changeit
     ```
2. **GKE**:
   - Crea un Secret: `kubectl create secret generic rmi-keystore --from-file=server.keystore`
   - Móntalo en `/app/certs/` y añade la propiedad JVM correspondiente.

> Esto cifra RMI/Gossip, **no** el tráfico Kafka. Para cifrar Kafka externo necesitas configurar SASL/SSL en el broker y en el AgenteGo.

---

## 📁 Archivos de despliegue del repositorio

| Archivo | Propósito |
|---|---|
| `deploy-gcp.sh` | Script turnkey de la Opción A (build, push, VM, firewall, arranque) |
| `.env.example` | Plantilla de variables (cópiala a `.env`) |
| `docker-compose.gcp.yml` | Stack de producción con imágenes de Artifact Registry |
| `docker-compose.yml` | Stack local (build directo, `EXTERNAL_HOST` por defecto `localhost`) |
| `k8s-deployment.yaml` | Manifiesto completo de GKE (Kafka + nodos + Client) |
| `.dockerignore` | Acelera los builds y excluye `target/`, `.git`, docs, AgenteGo |
