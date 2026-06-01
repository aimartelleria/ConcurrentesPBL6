# 🛡️ Gateway de ingesta NiFi con verificación de licencia

NiFi se sitúa **entre los agentes Go y Kafka** como gateway de ingesta. Autentica
a cada collector mediante su **licencia** (API key + portátil) comprobándola contra
la base de datos `webhardmon` (tabla `licencia`, `activa = 1`). Solo las licencias
activas se publican en Kafka; el resto se rechazan con **HTTP 401**.

```
[Agente Go] --HTTP POST /telemetry--> [NiFi gateway] --verifica licencia (MySQL)--> ¿activa=1?
   headers:                                                                          │ sí → PublishKafka → topic "telemetry"
   X-License-Code = codigo                                                           │ no → HTTP 401 (rechazado)
   X-Portatil     = portatil
   body: Avro binario
```

> **Por qué NiFi (justificación):** desacopla a los agentes del broker (no manejan
> credenciales ni topología de Kafka), centraliza la **autenticación por licencia**
> con revocación inmediata (`activa = 0` desde el panel) y aporta trazabilidad de la
> ingesta. Coste asumido: un salto de red adicional.

---

## 1. Levantar la infraestructura

La ingesta (NiFi + MySQL) está en `docker-compose.ingest.yml` y se fusiona con el
compose "core" (que aporta `kafka`). Comparten red, por eso NiFi resuelve `kafka`/`mysql`.

```bash
# Coloca antes el driver MySQL en nifi/drivers/ (ver nifi/drivers/README.md)

# Local (core + ingesta):
docker compose -f docker-compose.yml -f docker-compose.ingest.yml up -d

# GCP (core de producción + ingesta):
docker compose -f docker-compose.gcp.yml -f docker-compose.ingest.yml up -d
```

- MySQL se inicializa con `db/init.sql` (esquema + seed de prueba).
- NiFi: consola en **https://localhost:8443/nifi** (usuario `admin`, contraseña la del compose).

---

## 2. Construir el flujo en NiFi (una sola vez)

### 2.1 Controller Services

**a) `DBCPConnectionPool`** (conexión a MySQL):
| Propiedad | Valor |
|---|---|
| Database Connection URL | `jdbc:mysql://mysql:3306/webhardmon` |
| Database Driver Class Name | `com.mysql.cj.jdbc.Driver` |
| Database Driver Location(s) | `/opt/nifi/drivers/mysql-connector-j-8.4.0.jar` |
| Database User / Password | `root` / `root` |

**b) `SimpleDatabaseLookupService`** (búsqueda de la licencia):
| Propiedad | Valor |
|---|---|
| Database Connection Pool Service | *(el DBCPConnectionPool de arriba)* |
| Table Name | `licencia` |
| Lookup Key Column | `codigo` |
| Lookup Value Column | `activa` |

**c) `StandardHttpContextMap`** (necesario para el par request/response).

Habilita los tres (rayo ⚡).

### 2.2 Procesadores (encadenados)

1. **`HandleHttpRequest`**
   - Listening Port: `8081`
   - Allowed Paths: `/telemetry`
   - HTTP Context Map: `StandardHttpContextMap`
   - → crea un FlowFile con el cuerpo Avro y los headers como atributos
     (`http.headers.x-license-code`, `http.headers.x-portatil`).

2. **`LookupAttribute`** (verifica que la licencia existe y obtiene `activa`)
   - Lookup Service: `SimpleDatabaseLookupService`
   - Dynamic property → **`licencia.activa`** = `${http.headers.x-license-code}`
   - Relación **`unmatched`** (código inexistente) → va al paso de rechazo (5).
   - Relación **`matched`** → continúa al paso 3.

3. **`RouteOnAttribute`**
   - Dynamic property → **`valida`** = `${licencia.activa:equals('1')}`
   - Relación **`valida`** → paso 4 (publicar).
   - Relación **`unmatched`** (activa = 0) → paso de rechazo (5).

4. **`PublishKafka_2_6`** (rama válida)
   - Kafka Brokers: `kafka:9092`
   - Topic Name: `telemetry`
   - Use Transactions: false
   - relación `success` → **`HandleHttpResponse`** con **HTTP Status Code = 200**.

5. **`HandleHttpResponse`** (rama de rechazo)
   - HTTP Status Code: **401**
   - Conecta aquí `unmatched` de `LookupAttribute` y `unmatched` de `RouteOnAttribute`.

> **Opcional (endurecer):** para exigir además que el `portatil` coincida con el de
> la licencia, sustituye `SimpleDatabaseLookupService` por `DatabaseRecordLookupService`
> (devuelve varias columnas) y añade en el `RouteOnAttribute`:
> `${licencia.activa:equals('1'):and(${licencia.portatil:equals(${http.headers.x-portatil})})}`.

Arranca todos los procesadores (Start).

---

## 3. Ejecutar el agente Go

```bash
cd AgenteGo-main

# Licencia activa del seed -> aceptada (HTTP 200)
go run . -mode=publisher -nifi-url="http://localhost:8081/telemetry" \
         -codigo="DEMO-KEY-0001" -portatil="portatil-01"

# Licencia inactiva -> rechazada (HTTP 401)
go run . -mode=publisher -nifi-url="http://localhost:8081/telemetry" \
         -codigo="DEMO-KEY-0002" -portatil="portatil-02"
```

El agente imprime `telemetría aceptada por NiFi (HTTP 200)` o
`licencia RECHAZADA por NiFi (HTTP 401)` según el caso.

---

## 4. Gestión de licencias

- **Alta**: el panel web inserta en `licencia` (`codigo`, `empresa_id`, `portatil`, `activa=1`).
- **Revocación inmediata**: `UPDATE licencia SET activa = 0 WHERE codigo = '...';` →
  NiFi empieza a rechazar ese collector en el siguiente envío, sin reiniciar nada.

---

## 5. Notas de seguridad y producción

- **HTTPS**: en producción, configura un `StandardRestrictedSSLContextService` en
  `HandleHttpRequest` para que el `codigo` no viaje en claro. El agente Go funciona
  igual cambiando `-nifi-url` a `https://...`.
- **Cluster NiFi**: para HA, despliega 2+ nodos NiFi coordinados por ZooKeeper con un
  balanceador delante del puerto 8081. Para la demo basta un nodo.
- **Driver JDBC**: recuerda colocar el `.jar` en `nifi/drivers/` (ver su README).
