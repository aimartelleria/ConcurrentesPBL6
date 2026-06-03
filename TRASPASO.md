# 🔄 Traspaso de conversación — WebHardMon / Cluster RMI (PBL6)

Estado para continuar en una conversación nueva. Resume qué se ha hecho, dónde está
cada cosa, las convenciones acordadas y lo que falta.

## Repos y último commit
| Repo | Local | Rama | HEAD | Qué es |
|---|---|---|---|---|
| Proyecto (mío) | `C:\Users\aimar\Downloads\files` | main | **`1e214c7`** | Cluster RMI Java + agente Go + docs |
| Infra (equipo) | `C:\Users\aimar\Downloads\webhardmon-infra` | main | **`aa0b19e`** | `HardMonPBL6/Infraestructure-2-PBL6` (OpenTofu + Ansible) |
| App panel | clonado en `..\WebHardMon` | — | — | `HardMonPBL6/WebHardMon` (Spring Boot, referencia) |

GitHub: proyecto = `aimartelleria/ConcurrentesPBL6`; infra = `HardMonPBL6/Infraestructure-2-PBL6`.

## Arquitectura (pipeline canónico)
```
Agente Go (PCs usuario) --JSON + X-License-Code--> NiFi (gateway, nube local)
   NiFi: valida licencia en MySQL telemetriadb (vista licencia_lookup, codigo->empresa_id+nombre),
         enriquece, serializa a Avro (Schema Registry) --> Kafka topic "telemetry"
Kafka --> rmi-client (bridge, gcp-a, Virtual Threads):
   - KafkaAvroDeserializer (Schema Registry)
   - StressScore por RMI (round-robin + failover) en el cluster rmi-server (2-3 nodos)
   - ack manual + dead-letter "telemetry.DLT" (at-least-once)
   - escribe Cassandra webhardmon.mediciones (hot) + HDFS Parquet /data/telemetry (batch, best-effort)
Panel web (Spring Boot, gcp-b) lee MySQL + Cassandra + Grafana.
```
3 nubes (local Proxmox + GCP-A streaming + GCP-B analítica/servicio) unidas por **WireGuard**.
Agentes entran por **Cloudflare Tunnel** (no por WireGuard).

## Contratos / convenciones CLAVE (no cambiar sin acordar)
- **Esquema Avro**: `schemas/telemetry.avsc`. Campos: `empresa_id`(long), `nombre`(string),
  `ts`(timestamp-millis), `cpu_percent`, `ram_percent`, `disco_percent`, `temperatura`?(double),
  `bateria_percent`?(double), `ram`(string), `almacenamiento`(string), `procesador`?(string).
  `stress_score` lo añade el cliente Java tras el cálculo RMI.
- **Cassandra**: keyspace `webhardmon`, tabla **`mediciones`** (PK `((empresa_id), ts, nombre)`),
  RF=3. (OJO: el modelo canónico es `mediciones`, NO `metricas` — se descartó esa variante.)
  El cliente escribe con `dfs.client.use.datanode.hostname=true` para HDFS.
- **MySQL**: BD `telemetriadb`, tablas `empresa/administrador/usuario/licencia` + vista
  `licencia_lookup` (JOIN licencia→usuario para que NiFi resuelva codigo→empresa_id+nombre
  SIN llamar a la API Java). Auth de agentes = vía MySQL, no la API.
- **HDFS**: NameNode `hdfs://10.10.1.21:9000` (proyecto tofu APARTE en `HDFS/`, no en el inventario
  Ansible), DataNodes .22/.23, repl 2, base `/data/telemetry`. Alcanzable desde gcp-a por WireGuard.
- **Schema Registry**: Confluent, puerto **8081** (gcp-a node-01).
- **RMI**: registry 1099, objeto fijo `RMI_OBJECT_PORT=1100`; `java.rmi.server.hostname` = nombre
  WireGuard del nodo. Imágenes `stressscore` (servidor) + `stressscore-bridge` (cliente), gcp-a.
- **Puertos**: Kafka 9092, SR 8081, Cassandra 9042, MySQL 3306, NiFi 8080(UI)/8081(ingesta),
  Grafana 3000, Matomo **8282** (no 8080, choca con web), web 8080, HDFS 9000.
- **Imágenes**: se construyen con `docker/build-and-push.sh` (gcp-a / gcp-b / Harbor local).
  Las Java (`stressscore`, `stressscore-bridge`) y `web` se construyen desde **submódulos**
  (`services/stressscore` -> mi repo; `services/web` -> WebHardMon).

## Hecho (mi parte) — repo proyecto `1e214c7`
- **Cluster RMI** (`server/ClusterNode`, gossip P2P, Virtual Threads, sin SPOF; `RMI_OBJECT_PORT`).
- **Bridge** (`client/Client`): Kafka(SR Avro) → StressScore RMI → Cassandra `mediciones` + HDFS
  Parquet (`HdfsParquetWriter`); ack manual + dead-letter.
- **Agente Go**: recoge CPU(uso+modelo)/RAM(uso+total)/disco(uso+total)/temperatura/**batería**;
  envía **JSON** a NiFi con `X-License-Code`/`X-Portatil`. Batería: sysfs(Linux)/WMI(Windows), nullable.
- **Esquemas**: `schemas/telemetry.avsc`, `db/schema-cassandra.cql` (mediciones), `db/init.sql`
  (telemetriadb + licencia_lookup).
- **Docker/compose**: `server/Dockerfile`, `client/Dockerfile`, `docker-compose.yml` (core local:
  kafka+SR+cassandra+3 nodos+client), `docker-compose.ingest.yml` (NiFi+MySQL overlay),
  `docker-compose.gcp.yml`, `deploy-gcp.sh`, `.env.example`, `.env.cloud.example`, `k8s-deployment.yaml`.
- **CI**: `.github/workflows/sonar.yml` (SonarCloud Java21+Go) + `sonar-project.properties`.
- **Docs**: README, ARQUITECTURA, DOCUMENTACION_COMPLETA, GUIA_Y_RESULTADOS, QUICK_REFERENCE,
  NIFI_GATEWAY, CONTAINER_CONTRACT, INFRA_INTEGRATION, HDFS-INTEGRATION, DEPLOY_GCP + `index.html`
  (panel; embebe los .md). Todos con Kafka/NiFi/Cassandra/HDFS/batería al día.

## Hecho (mi parte en la infra) — `aa0b19e`
- Submódulos `services/stressscore` (mi repo) y `services/web` (WebHardMon).
- `build-and-push.sh`: construye `stressscore`, `stressscore-bridge` (gcp-a) y `web` (gcp-b) desde submódulos.
- Roles Ansible: `docker`, `stressscore`, `web` + playbooks `stressscore.yml`, `web.yml`.
- group_vars `java.yml`, `java_bridge.yml` (incl. `bridge_hdfs_uri`), `web.yml`.
- Cassandra `mediciones` + MySQL `telemetriadb`/licencia_lookup alineados en `docker/`.

## ⚠️ Pendiente / del compañero (NO hacer sin coordinar)
- **El compañero va a subir SUS roles base** (zookeeper, kafka, schema_registry, cassandra, mysql,
  hbase, elasticsearch, grafana, matomo) y **mapreduce**. Por eso se DESHIZO mi commit `efc8b39`
  (que los había creado) para evitar solape. **Esperar a que él los suba.**
- **NiFi**: lo despliega el compañero (rol + cloudflared). No tocar.
- Tras su push: reconciliar y, si procede, aplicar ajustes a su tabla de descripciones
  (matomo→8282, hbase crea `webhardmon_hourly` con pre-split, grafana datasources
  Cassandra/MySQL/HBase REST). El **flujo NiFi** se monta en la UI (ver `NIFI_GATEWAY.md`).
- Secretos: `terraform.tfvars` reales + `ansible-vault` (passwords MySQL/Grafana/Matomo, token cloudflared),
  `SONAR_TOKEN` en GitHub, service token de Cloudflare Access para agentes.
- `ansible-galaxy collection install community.docker`; `vm.max_map_count` para ES.

## Cómo se levanta todo (cuando estén los roles del compañero)
```
tofu apply                       # provisiona + genera ansible/inventory.ini
cd docker && ./build-and-push.sh # construye/sube imágenes
ansible-playbook -i ansible/inventory.ini ansible/site.yml
# + NiFi (compañero) + agentes Go (-nifi-url=https://ingest.<zona>/telemetry -codigo=.. -portatil=..)
```

## Flecos opcionales no implementados
- **Timeout RMI** (`-Dsun.rmi.transport.tcp.responseTimeout`) para nodo "colgado".
- **Replayer de la dead-letter** (reprocesar `telemetry.DLT` al recuperarse el clúster).
- `ClusterNode.processTelemetry(byte[])` es legacy (sin batería); el camino activo es `executeTask`.

## 🔴 ACTUALIZACIÓN CRÍTICA — esquema canónico del equipo (¡realinear el Java!)
El compañero subió TODOS los roles base + nifi + mapreduce (infra HEAD movió a `3c41373`).
Y nos dio el **esquema canónico REAL** del payload (NiFi/Kafka/Parquet/MapReduce), que **NO
coincide** con el de mi código Java. Hay que reconciliarlo en la sesión nueva.

**Esquema canónico (ya actualizado en `schemas/telemetry.avsc` y `NIFI_GATEWAY.md`):**
`licencia(str), portatil(str), timestamp(long ms), uso_procesador, uso_ram, cantidad_ram,
uso_almacenamiento, cantidad_almacenamiento, bateria, temperatura, stressScore, empresa_id(int, default 0)`

**MySQL canónico (modelo PLANO, db `webhardmon`):** `empresa(id,nombre)` + `licencia(codigo,
portatil, activa, empresa_id)`. NiFi valida + saca empresa_id con:
`SELECT l.activa, l.empresa_id FROM licencia l WHERE l.codigo=:licencia AND l.portatil=:portatil`.
Conexión NiFi→MySQL: `10.0.0.30:3307` (WireGuard), Schema Registry `10.0.0.20:8081`.

**Arquitectura aclarada:** NiFi escribe el **Parquet en HDFS** (no mi bridge), y `stressScore`
va EN el payload (lo calcula el servidor RMI). → revisar si mi `HdfsParquetWriter` y el rol del
bridge siguen teniendo sentido o son redundantes.

**TODO realineamiento (sesión nueva):**
1. `client/Client.java`: leer los campos canónicos (`uso_procesador`, `cantidad_ram`, `portatil`,
   `stressScore`, `empresa_id`…) en vez de `cpu_percent`/`nombre`/`stress_score`.
2. `db/init.sql`: pasar al modelo plano `licencia(codigo,portatil,activa,empresa_id)` en `webhardmon`
   (quitar el `usuario`/`licencia_lookup` actual) — o confirmar con el equipo (hay versiones MySQL en conflicto).
3. Cassandra `mediciones`: decidir mapeo de columnas a los nombres canónicos (o mapear en el cliente).
4. `HdfsParquetWriter`: ¿se elimina (NiFi escribe Parquet) o se alinea a los campos canónicos? DECIDIR.
5. Confirmar dónde se calcula `stressScore` (¿colector llama a RMI?, ¿bridge?) — afecta al rol del bridge.

## Última acción en esta ventana
1. Deshecho el commit `efc8b39` de la infra (reset `aa0b19e` + `--force-with-lease`) para no solapar.
   El compañero subió luego sus roles (`6732dc8`); rebase limpio → mis `stressscore`/`web` conviven.
2. Actualizado el contrato Avro (`telemetry.avsc`) + `NIFI_GATEWAY.md` al esquema canónico con `empresa_id`.
   PENDIENTE: el realineamiento del Java (lista de arriba). El flujo NiFi (incl. enriquecer empresa_id)
   se monta en la UI; el rol solo despliega el contenedor.
