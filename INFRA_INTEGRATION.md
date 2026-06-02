# 🔌 Integración con `webhardmon-infra` (split: 2 imágenes)

Cómo encaja el servicio Java en la infra del equipo (`sebastian-zm/webhardmon-infra`),
manteniendo la **separación en dos imágenes**. La infra ya reserva un hueco
(`docker/java-stressscore/`, `ansible/group_vars/java.yml`) pensado para **una sola**
imagen; este documento detalla lo que hay que **ajustar para alojar las dos**.

## Las dos imágenes (ambas a gcp-a)

| Imagen | Rol | Nodos | Puertos | Consume/Escribe |
|---|---|---|---|---|
| `stressscore` | Nodo RMI (`ClusterNode`): gossip + ejecuta `StressTask` | node-02 / node-03 | RMI registry `PORT` (1099) + objeto `RMI_OBJECT_PORT` (1100) | — |
| `stressscore-bridge` | Puente (`Client`): consume Kafka(Avro/SR) → StressScore por RMI → escribe Cassandra | 1 (p.ej. node-02) | sin entrada RMI | Kafka, Schema Registry, Cassandra |

> El **bridge** es cliente RMI de los nodos `stressscore` (round-robin + failover) y
> productor de la dead-letter. Si el clúster RMI cae, los mensajes van a `telemetry.DLT`.

## Contrato confirmado (leído de sus `group_vars`)

| Recurso | Valor |
|---|---|
| Red | WireGuard; host anunciado = IP interna GCP (`inventory_hostname`) |
| Registry gcp-a | `europe-southwest1-docker.pkg.dev/<proj-a>/webhardmon-a-docker` |
| Kafka | 3 brokers, `:9092`, 3 particiones, RF=3 |
| Schema Registry | node-01, **`:8081`**, compat. BACKWARD, wire-format Confluent |
| Cassandra | keyspace `webhardmon`, RF=3, cluster `webhardmon-stream` |
| MySQL/licencia | BD `webhardmon`, `licencia(codigo, activa, empresa_id, portatil)` (modelo codigo+portatil) |
| Ingesta agentes | `https://ingest.<zona>` (Cloudflare Access: cabeceras `CF-Access-Client-Id/Secret`) |

## Qué hay que ajustar en `webhardmon-infra`

### 1. Build de imágenes
El placeholder `docker/java-stressscore/Dockerfile` asume proyecto **mono-módulo**; el
nuestro es **multi-módulo** (common/server/client). Las imágenes se construyen desde
**este repo** con los Dockerfiles ya existentes:
- `stressscore`         ← `server/Dockerfile`
- `stressscore-bridge`  ← `client/Dockerfile`

Y se suben a gcp-a con la convención de su `build-and-push.sh`:
```
europe-southwest1-docker.pkg.dev/<GCP_A_PROJECT>/webhardmon-a-docker/stressscore:<TAG>
europe-southwest1-docker.pkg.dev/<GCP_A_PROJECT>/webhardmon-a-docker/stressscore-bridge:<TAG>
```
En su `docker/build-and-push.sh`, añadir al mapa `CLOUD`:
```bash
[java-stressscore]=gcp-a [stressscore-bridge]=gcp-a
```
(o construir en este repo y solo desplegar allí — su `docker/README.md` lo permite).

### 2. `ansible/group_vars/java.yml`  → SOLO los nodos RMI (`stressscore`)
Quitar de aquí kafka/cassandra/SR (eso es del bridge) y dejar:
```yaml
javaapp_image: "{{ container_registry }}/stressscore:{{ image_tag }}"
javaapp_rmi_registry_port: 1099
javaapp_rmi_object_port: 1100                 # se inyecta como RMI_OBJECT_PORT
javaapp_rmi_server_hostname: "{{ inventory_hostname }}"
javaapp_seeds: "{{ groups['java'] | map('regex_replace','$',':1099') | join(' ') }}"
```
Mapeo a env del contenedor `stressscore`:
`NODE_ID={{ inventory_hostname }}` · `HOST={{ inventory_hostname }}` ·
`PORT={{ javaapp_rmi_registry_port }}` · `RMI_OBJECT_PORT={{ javaapp_rmi_object_port }}` ·
`SEEDS={{ javaapp_seeds }}` · `JVM_OPTS=-Djava.rmi.server.hostname={{ javaapp_rmi_server_hostname }}`

### 3. `ansible/group_vars/java_bridge.yml`  → NUEVO (el `Client`)
```yaml
bridge_image: "{{ container_registry }}/stressscore-bridge:{{ image_tag }}"
bridge_kafka_bootstrap: "{{ kafka_brokers | map('regex_replace','$',':9092') | join(',') }}"
bridge_schema_registry_url: "http://{{ hostvars[schema_registry_node].ansible_host }}:{{ hostvars[schema_registry_node].schema_registry_port | default(8081) }}"
bridge_cassandra_contact_points: "{{ cassandra_nodes | map('regex_replace','$',':9042') | join(',') }}"
bridge_cassandra_local_dc: "datacenter1"      # CONFIRMAR el DC real de Cassandra
bridge_cassandra_keyspace: "webhardmon"
bridge_seeds: "{{ groups['java'] | map('regex_replace','$',':1099') | join(' ') }}"
```
Mapeo a env del contenedor `stressscore-bridge`:
`KAFKA_BOOTSTRAP_SERVERS={{ bridge_kafka_bootstrap }}` ·
`SCHEMA_REGISTRY_URL={{ bridge_schema_registry_url }}` ·
`CASSANDRA_CONTACT_POINTS={{ bridge_cassandra_contact_points }}` ·
`CASSANDRA_LOCAL_DC={{ bridge_cassandra_local_dc }}` ·
`CASSANDRA_KEYSPACE={{ bridge_cassandra_keyspace }}` ·
y los **seeds RMI como argumentos**: `{{ bridge_seeds }}`.

### 4. Inventario
- Grupo `[java]` = node-02, node-03 (nodos RMI `stressscore`).
- Grupo `[java_bridge]` = 1 host (p.ej. node-02) que corre el `stressscore-bridge`.

## Pendiente de confirmar con el compañero
1. **`CASSANDRA_LOCAL_DC`**: su `cassandra.yml` no fija el nombre de datacenter → confirmar (¿`datacenter1`?).
2. **Nombre del topic** Kafka (`telemetry`) y subject del SR (`telemetry-value`).
3. Que acepta **dos imágenes** (`stressscore` + `stressscore-bridge`) en vez de una.

> El esquema Avro que NiFi publica debe coincidir con `schemas/telemetry.avsc` de este repo.
