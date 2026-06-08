# 🛡️ Gateway de ingesta NiFi — flujo y enriquecimiento (`empresa_id` + `nombre`)

NiFi corre en un **CT LXC de la nube local (Proxmox)**. Recibe la telemetría de los
portátiles por **HTTP (Cloudflare Tunnel → listener 8081)**, la valida, la enriquece y
la publica en **Kafka (Avro)**. El flujo se construye en la **UI de NiFi** (el rol de
Ansible solo despliega el contenedor + cloudflared).

> **Fuente de verdad = el bridge Java (`client/Client.java`).** NiFi (que manejamos nosotros)
> emite el Avro con los **nombres de campo que el bridge lee**, no al revés. El **stressScore
> NO lo añade NiFi**: lo calcula el clúster RMI (disparado por el bridge) y el **bridge** escribe
> Cassandra + **HDFS Parquet**. NiFi NO escribe el Parquet.

## Flujo (el que debe estar montado en la UI)
1. **Escucha HTTP POST** con payload JSON del colector (`ListenHTTP`/`HandleHttpRequest`, puerto 8081).
   La licencia (`codigo`) y el identificador de portátil viajan en cabeceras `X-License-Code` / `X-Portatil`.
2. **Valida la licencia y resuelve `empresa_id` + `nombre`** contra MySQL (LookupService JDBC) usando la
   vista `licencia_lookup` (resuelve el JOIN `licencia → usuario` por `codigo`):
   ```sql
   SELECT activa, empresa_id, nombre
   FROM licencia_lookup
   WHERE codigo = :licencia
   ```
   - Descarta el registro si `activa != 1` (responde 401/403 al colector).
   - El `nombre` devuelto es el `nombre_ordenador` canónico de la BD (NO la cabecera `X-Portatil`,
     que solo sirve de cross-check).
3. **Enriquece + mapea** el record a los campos que lee el bridge. La columna **Origen**
   son las **claves JSON** del payload del colector (las `json:` tags de `metrics_shared.go`),
   NO los nombres del struct Go. Varios campos requieren **renombrar** (ES↔EN), no son copia directa:
   | Campo Avro | Origen (clave JSON del colector) | Transformación en NiFi |
   |---|---|---|
   | `empresa_id` | MySQL `licencia_lookup` | directo (long) |
   | `nombre` | MySQL `licencia_lookup` (`nombre_ordenador`) | directo |
   | `ts` | `timestamp` (Unix **segundos**) | **×1000 → milisegundos** |
   | `cpu_percent` | `cpu_percent` | directo |
   | `ram_percent` | `ram_percent` | directo |
   | `disco_percent` | `disk_percent` | **renombrar** `disk_percent` → `disco_percent` |
   | `temperatura` | `temp_c` | **renombrar** `temp_c` → `temperatura` (nullable) |
   | `bateria_percent` | `bateria_percent` | directo (nullable) |
   | `ram` | `ram_total` (bytes) | **formatear a texto** (p.ej. "16 GB") |
   | `almacenamiento` | `disk_total` (bytes) | **formatear a texto** (p.ej. "512 GB") |
   | `procesador` | `cpu_model` | **renombrar** `cpu_model` → `procesador` (nullable) |
4. **Serializa a Avro** contra el **Confluent Schema Registry** y publica en **Kafka** (`telemetry`).

> El `stressScore` y la escritura en **Cassandra + HDFS Parquet** ocurren **aguas abajo, en el bridge**
> (tras el cálculo RMI). NiFi termina su trabajo al publicar en Kafka.

## Conectividad MySQL (desde el CT de NiFi, vía WireGuard)
| | Valor |
|---|---|
| Host | `10.0.0.30` (gateway GCP-B) → rutea a `10.30.2.11` |
| Puerto | `3307` (el contenedor MySQL mapea 3307→3306) |
| BD | `telemetriadb` |
| Usuario/clave | `mysql_app_user` / `mysql_app_password` (rol Ansible de MySQL, por vault) |

## Esquema MySQL relevante (modelo relacional — ver `db/init.sql`)
```sql
-- empresa(id, nombre)
-- usuario(id, nombre, nombre_ordenador, empresa_id)   -- nombre_ordenador == `nombre` en Cassandra
-- licencia(id, codigo, activa, fecha_creacion, usuario_id)
-- Vista para NiFi (una sola clave: codigo):
CREATE OR REPLACE VIEW licencia_lookup AS
SELECT l.codigo, l.activa, u.empresa_id, u.nombre_ordenador AS nombre
FROM licencia l JOIN usuario u ON u.id = l.usuario_id;
```
NiFi consulta **`licencia_lookup`** por `codigo`: valida (`activa=1`) y obtiene `empresa_id` + `nombre`
en la misma consulta, sin llamar a la API Java.

## Esquema Avro (Schema Registry, 10.0.0.20:8081, compat BACKWARD)
Ver **`schemas/telemetry.avsc`** (alineado con el bridge). Campos:
`empresa_id(long, default 0), nombre, ts(ms), cpu_percent, ram_percent, disco_percent,
temperatura?, bateria_percent?, ram(texto), almacenamiento(texto), procesador?`.
El `default: 0` de `empresa_id` mantiene compat BACKWARD. **No hay `stressScore` en este topic.**

## Nota MapReduce (downstream, capa batch del compañero)
El job MapReduce lee el Parquet que escribe `HdfsParquetWriter` y agrupa por empresa + hardware + hora:
```
{empresa_id}|{ram}|{almacenamiento}|{yyyyMMddHH UTC}
```
En este contrato `ram`/`almacenamiento` son **texto** (p.ej. "16 GB"). El mapper los parsea a GB
numéricos para construir el tier (`ramGb`/`stoGb`) y escribir los agregados en HBase.

## Despliegue (rol Ansible) — Clúster NiFi HA
El rol `ansible/roles/nifi` (repo de infra) despliega un **clúster NiFi de 3 nodos** en la nube
local (`var.topology.nifi = 3`), con **alta disponibilidad**:

- **Coordinación: ZooKeeper local** (ensemble de 3, co-localizado en los mismos CTs que NiFi).
  Es independiente del ZK de gcp-a (Kafka) → la ingesta no depende de otra nube. Quórum 2 = tolera 1 caída.
- **Balanceo: Cloudflare en el edge.** `cloudflared` corre en **cada nodo** con el **mismo token**:
  Cloudflare reparte las conexiones entre los conectores sanos y cada uno enruta a su NiFi local
  (`localhost:8081`). **No hay LB interno** (que sería un nuevo SPOF). El `HandleHttpRequest` corre
  en todos los nodos.
- **Flujo replicado:** se configura **una sola vez** vía REST API (`files/configure_flow.py`,
  idempotente, `run_once`) contra el coordinador; NiFi replica el flujo a todos los nodos.
- **Modo HTTP interno** (sin TLS) en la red local de confianza — evita el problema de confianza
  mutua de certificados entre nodos. El cifrado de cara al exterior lo pone Cloudflare.
- Puertos del clúster: `11443` (protocolo entre nodos), `6342` (load-balance de colas),
  `2181/2888/3888` (ZK local). `NIFI_SENSITIVE_PROPS_KEY` idéntica en todos los nodos (vault).

> **HA real de extremo a extremo:** el clúster RMI (cómputo) y ahora el gateway NiFi (ingesta)
> son ambos tolerantes a la caída de un nodo. Caída de 1 NiFi → Cloudflare deja de enrutar a su
> conector y el resto sigue ingiriendo; Kafka + ack manual + DLT protegen los datos ya admitidos.
