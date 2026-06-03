# 🛡️ Gateway de ingesta NiFi — flujo y enriquecimiento de `empresa_id`

NiFi corre en un **CT LXC de la nube local (Proxmox)**. Recibe la telemetría de los
portátiles por **HTTP (Cloudflare Tunnel → listener 8081)** y la valida, enriquece,
publica en Kafka (Avro) y la escribe en HDFS (Parquet). El flujo se construye en la
**UI de NiFi** (el rol de Ansible solo despliega el contenedor + cloudflared).

## Flujo (el que debe estar montado en la UI)
1. **Escucha HTTP POST** con payload JSON del colector (`ListenHTTP`/`HandleHttpRequest`, puerto 8081).
2. **Valida la licencia contra MySQL** (LookupService JDBC) y **obtiene `empresa_id`** en la misma consulta:
   ```sql
   SELECT l.activa, l.empresa_id
   FROM licencia l
   WHERE l.codigo = :licencia AND l.portatil = :portatil
   ```
   - Descarta el registro si `activa != 1`.
3. **Enriquece** el registro con `empresa_id` (atributo del FlowFile y/o campo del record),
   para que esté disponible en los pasos siguientes.
4. **Serializa a Avro** contra el **Confluent Schema Registry** y publica en **Kafka** (`telemetry`).
5. **Escribe el registro en HDFS** en **Parquet** (capa batch del patrón Lambda).

> `empresa_id` debe ir **en el Avro de Kafka Y en el Parquet de HDFS** — es la dimensión
> de agrupación que usa el job MapReduce. Sin él, el batch layer no agrupa por empresa.

## Conectividad MySQL (desde el CT de NiFi, vía WireGuard)
| | Valor |
|---|---|
| Host | `10.0.0.30` (gateway GCP-B) → rutea a `10.30.2.11` |
| Puerto | `3307` (el contenedor MySQL mapea 3307→3306) |
| BD | `webhardmon` |
| Usuario/clave | `mysql_app_user` / `mysql_app_password` (rol Ansible de MySQL, por vault) |

## Esquema MySQL relevante
```sql
CREATE TABLE empresa  ( id INT PRIMARY KEY AUTO_INCREMENT, nombre VARCHAR(255) );
CREATE TABLE licencia ( id INT PRIMARY KEY AUTO_INCREMENT,
                        codigo VARCHAR(255) UNIQUE,   -- API key del colector
                        portatil VARCHAR(255),        -- hostname del portátil
                        activa TINYINT(1) DEFAULT 1,
                        empresa_id INT,
                        FOREIGN KEY (empresa_id) REFERENCES empresa(id) );
```
(Modelo **plano**: `licencia` lleva `codigo`, `portatil`, `activa` y `empresa_id` directamente.)

## Esquema Avro canónico (Schema Registry, 10.0.0.20:8081, compat BACKWARD)
Ver **`schemas/telemetry.avsc`**. Campos del payload del colector + el añadido:
`licencia, portatil, timestamp(ms), uso_procesador, uso_ram, cantidad_ram,
uso_almacenamiento, cantidad_almacenamiento, bateria, temperatura, stressScore`
y **nuevo**:
```json
{ "name": "empresa_id", "type": "int", "default": 0, "doc": "ID de la empresa propietaria del portátil" }
```
El `default: 0` es **imprescindible** para no romper consumidores existentes (compat BACKWARD).

## Expectativa del job MapReduce (downstream)
Agrupa los Parquet por:
```
{empresa_id}|{cantidad_ram redondeada a GB}|{cantidad_almacenamiento redondeada a GB}|{yyyyMMddHH UTC}
```
→ por eso `empresa_id` **debe** estar en el Parquet.

## Despliegue (rol Ansible)
El rol `ansible/roles/nifi` (repo de infra) levanta el contenedor NiFi (UI 8080, ingesta 8081)
+ cloudflared (túnel saliente). El **flujo de arriba se importa/monta en la UI** — no está en el rol.

> ⚠️ **Pendiente de realinear (consumidor Java):** el esquema canónico de arriba usa
> `uso_procesador`/`cantidad_ram`/`portatil`/`stressScore`/`empresa_id(int)`, distinto de los
> nombres que aún usan `client/Client.java`, `HdfsParquetWriter` y la tabla Cassandra `mediciones`
> (`cpu_percent`/`ram`/`nombre`/`stress_score`/`empresa_id long`). Hay que reconciliarlo (ver TRASPASO.md).
