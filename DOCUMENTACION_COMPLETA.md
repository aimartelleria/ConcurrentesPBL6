# 📘 Memoria Técnica y Documentación Global del Proyecto

Este documento proporciona una visión unificada, profunda y definitiva sobre la arquitectura, los componentes y la **justificación de las decisiones tecnológicas** del proyecto de Cluster RMI P2P y Telemetría.

---

## 1. 🎯 Visión General

El sistema es una **red distribuida de computación *Peer-to-Peer* (P2P) y procesamiento de telemetría sin Punto Único de Fallo (SPOF)**. Su propósito es recoger datos de hardware (telemetría), validarlos en una pasarela de ingesta, encolarlos para evitar pérdida de datos en picos de carga, distribuirlos a un enjambre de nodos de computación que se autodescubren mediante un protocolo *Gossip* y persistir el resultado.

A nivel funcional, el sistema:
1. **Recopila** métricas en máquinas externas (agentes Go).
2. **Valida y gobierna la ingesta** mediante un *gateway* Apache NiFi que autentica por licencia contra la BD de la aplicación (MySQL) y serializa a Avro con Schema Registry.
3. **Serializa y encola** de forma altamente eficiente en Apache Kafka.
4. **Distribuye** el cálculo (StressScore) a través de una malla P2P de nodos Java vía RMI.
5. **Persiste** las mediciones enriquecidas en Cassandra.
6. **Resiste** caídas de nodos, redireccionando el tráfico automáticamente de manera distribuida, con *ack* manual y *dead-letter* en el consumidor para garantía *at-least-once*.

---

## 2. 🏗️ Arquitectura del Sistema

La arquitectura está formada por varios subsistemas especializados que operan de forma desacoplada:

```text
┌────────────────┐  HTTP   ┌──────────────────┐  Avro + Schema Registry  ┌──────────────────┐
│   Agente Go    ├────────►│   NiFi (gateway) ├─────────────────────────►│      Kafka       │
│  (Data Source) │  POST   │ valida licencia  │     PublishKafka         │  topic telemetry │
└────────────────┘         │  (MySQL: app DB) │                          └────────┬─────────┘
                           └──────────────────┘                                   │ Consumo
                                                                                  ▼
                 ┌────────────────────────────────────────────────────────┐
                 │                  CLIENTE JAVA (rmi-client)             │
                 │  KafkaConsumer (KafkaAvroDeserializer, ack manual +    │
                 │  dead-letter telemetry.DLT) + RMI Bootstrap + LB       │
                 └────────────────────────────────────────────────────────┘
                           │                  │                  │
                RMI / TLS  │                  │ RMI / TLS        │ RMI / TLS
                           ▼                  ▼                  ▼
                    ┌─────────┐        ┌─────────┐        ┌─────────┐
    Protocolo       │  NODO 1 │◄──────►│  NODO 2 │◄──────►│  NODO 3 │   (rmi-server x3)
    Gossip P2P      │(Compute)│        │(Compute)│        │(Compute)│
    (Membresía)     └─────────┘        └─────────┘        └─────────┘
                           │                  │                  │
                           └──────────────────┴──────────────────┘
                                              ▼
                                    ┌───────────────────┐
                                    │     Cassandra     │  keyspace webhardmon
                                    │ (mediciones, etc.)│  tabla mediciones
                                    └───────────────────┘
```

### Componentes Clave:
1. **Source (Agente Go):** Obtiene datos del sistema operativo y genera cargas útiles (*payloads*), que envía por HTTP al gateway de ingesta.
2. **Gateway de ingesta (Apache NiFi):** Se sitúa entre los agentes y Kafka. Autentica a cada collector por su **licencia** contra la BD de la aplicación (MySQL: tabla `licencia`, `activa = 1`), serializa a Avro gobernado por Schema Registry y publica en Kafka. Las licencias inactivas o inexistentes se rechazan con **HTTP 401**. (Detalle completo en `NIFI_GATEWAY.md`.)
3. **Buffer (Kafka):** Desacopla la emisión de datos de la capacidad y velocidad de procesamiento del cluster de Java. El topic `telemetry` transporta Avro con Schema Registry.
4. **Edge / Entrypoint (Cliente Java, `rmi-client`):** Actúa como un puente. Consume de Kafka con `KafkaAvroDeserializer`, calcula el StressScore vía RMI (balanceo Round-Robin tolerante a fallos) y persiste en Cassandra. Usa **ack manual** (`commitSync` con `enable.auto.commit=false`) y **dead-letter** (`telemetry.DLT`) para garantía *at-least-once*.
5. **Compute Grid (Servidores ClusterNode, `rmi-server` x3):** Malla de nodos que ejecutan el motor de cálculo genérico (`Task`), en particular `StressTask`. Mantienen su estado (quién está vivo/muerto) "cotilleando" (Gossip) entre sí.
6. **Almacenamiento:** **Cassandra** (keyspace `webhardmon`: tablas `mediciones`, `ordenadores`, `empresas`) para la serie temporal de telemetría, y **MySQL** como BD de la aplicación (empresa, administrador, usuario, licencia; autenticación de los agentes por licencia).

---

## 3. 🧠 Justificación Tecnológica (Stack)

Cada tecnología en este ecosistema no fue elegida al azar, sino que resuelve un problema específico del procesamiento o la arquitectura en red.

### 🌟 3.1. Java 21 y Virtual Threads (Project Loom)
En la parte del procesamiento RMI (Motor de Cálculo) y el puente (Cliente), la concurrencia tradicional generaría "cuellos de botella" graves: RMI bloquea los hilos durante la conexión y espera.
- **Justificación:** Los *Virtual Threads* permiten instanciar millones de hilos superligeros que se "aparcan" gratis cuando esperan I/O (la red), evitando que el motor se sature.
- **Resultado:** En nuestra implementación, el procesamiento concurrente de telemetría de Kafka y las peticiones RMI usan `Executors.newVirtualThreadPerTaskExecutor()`, logrando alto rendimiento sin un límite duro de *ThreadPools*.

### 🌟 3.2. Java RMI (Remote Method Invocation)
Sirve como vía de comunicación RPC (Remote Procedure Call) a nivel interno.
- **Justificación:** Otorga la capacidad de invocar métodos remotos (como `processTelemetry` o `executeTask(Task)`) en el clúster con serialización nativa de objetos complejos Java (como clases `Task`). El *overhead* de red es compensado al no requerir parseos de JSON/HTTP continuamente.
- **Resultado:** Las lógicas abstractas se pueden distribuir de forma nativa enviando *Bytecode*.

### 🌟 3.3. Protocolo Gossip P2P
Para tener un cluster *"master-less"* (sin un líder ni servidor centralizado).
- **Justificación:** Un servidor central o servicio estilo ZooKeeper añade complejidad y un punto único de fallo. *Gossip* emula el comportamiento de un virus o "chisme": cada nodo le cuenta a otro a quién conoce, propagando la información de salud y topología gradualmente por la red.
- **Resultado:** Alta tolerancia a la partición y fallos en hardware comercial estándar. O(N²) de coste pasivo pero inmensa robustez.

### 🌟 3.4. Apache Kafka
El bróker de mensajería distribuido.
- **Justificación:** Si los agentes de Go enviasen los datos en vivo directo a los nodos, la caída de estos haría perder métricas, y un aluvión de telemetría haría colapsar la capa de red con denegación de servicio (Backpressure problems). Kafka introduce **desacoplamiento** y actúa de "amortiguador" (*buffer*) y log distribuido.
- **Resultado:** Si el cluster RMI de Java se apaga o sufre, Kafka retiene ordenadamente los mensajes hasta que vuelva, permitiendo además el re-procesamiento si fuera necesario.

### 🌟 3.5. Golang (Agentes de Extracción)
El `AgenteGo` se encarga de extraer la métrica e iniciar el ciclo vital de los datos.
- **Justificación:** Go se compila como un binario estático, sin requerir una JVM de 200MB en las máquinas de donde obtiene los datos. Es altamente concurrente y consume ~15MB-30MB de memoria en ejecución. Ideal para *sidecars* y sistemas embebidos de monitorización.

### 🌟 3.6. Apache Avro + Schema Registry
Para la codificación del mensaje publicado en Kafka.
- **Justificación:** Frente a JSON que necesita descifrar strings, Avro comprime los datos de forma binaria apoyado en un *schema* fijo, reduciendo drásticamente (hasta en un 70%) el tamaño de banda ocupada en red y acelerando la de-serialización.
- **Gobierno del esquema:** un **Schema Registry** (Confluent en local; compatible con Apicurio) centraliza la definición del esquema (`schemas/telemetry.avsc`). NiFi serializa con él al publicar y el cliente Java deserializa con `KafkaAvroDeserializer`, garantizando compatibilidad y evolución controlada del contrato de datos.

### 🌟 3.7. Apache NiFi (Gateway de ingesta con verificación de licencia)
Pasarela que media entre los agentes Go y Kafka.
- **Justificación:** desacopla a los agentes del broker (no manejan credenciales ni topología de Kafka), centraliza la **autenticación por licencia** contra MySQL con revocación inmediata (`activa = 0`) y aporta trazabilidad de la ingesta. Solo las licencias activas se publican en `telemetry`; el resto se rechaza con HTTP 401.
- **Resultado:** control de acceso por collector sin tocar el código de los agentes, a coste de un salto de red adicional. Documentado en `NIFI_GATEWAY.md`.

### 🌟 3.8. Apache Cassandra
Almacén de la serie temporal de telemetría.
- **Justificación:** Cassandra ofrece escritura rápida y escalabilidad horizontal lineal para series temporales de alto volumen, ideal para el flujo continuo de mediciones. El keyspace `webhardmon` modela `mediciones` (particionada por `empresa_id` y ordenada por `ts DESC`), junto con `ordenadores` y `empresas`.
- **Justificación de la doble BD:** MySQL conserva el modelo relacional de la aplicación (empresa, administrador, usuario, licencia) y la autenticación de agentes; Cassandra absorbe la carga de escritura de telemetría sin penalizar la BD relacional.

### 🌟 3.7. TLS/SSL para Cifrado
Para la comunicación en el interior del cluster.
- **Justificación:** RMI en crudo carece de seguridad y transita en texto o bytes predecibles por la red (vulnerable a *man-in-the-middle* o ejecución de comandos remota maliciosa). Inyectando `-Djavax.net.ssl.trustStore` y fábricas de SSL personalizadas, el cluster RMI pasa a ser encriptado, pudiendo interconectar zonas geográficas diferentes con seguridad.

---

## 4. 🔀 Flujo Completo y Ciclo de Vida de los Datos

Para empaquetar cómo coopera todo, este es el viaje de 1 milisegundo de tu aplicación:

1. **[GO]** Un sensor en el Agente de Go detecta que la CPU local está al 90%. Empaqueta esta información.
2. **[GO -> NIFI]** Go envía la métrica por HTTP POST al gateway de NiFi, incluyendo las cabeceras de licencia (`X-License-Code`, `X-Portatil`).
3. **[NIFI]** NiFi verifica la licencia contra MySQL (`licencia.activa = 1`). Si es inválida, responde **HTTP 401** y descarta el dato. Si es válida, serializa a **Avro** (gobernado por Schema Registry) y publica en el topic `telemetry` de Kafka (HTTP 200 al agente).
4. **[BROKER]** Kafka almacena el paquete y se asegura de que exista persistencia y replicación.
5. **[JAVA CLIENT]** El `rmi-client` ("el consumidor"), mediante un bucle de `poll`, recibe el registro Avro y lo deserializa con `KafkaAvroDeserializer` (resolviendo el esquema vía Schema Registry).
6. **[JAVA CLIENT -> LOAD BALANCER]** El `Client` verifica su caché Gossip. Descubre que hay 3 nodos vivos. El puntero *round-robin* selecciona el `NODO 2` y despacha el cálculo en un Virtual Thread.
7. **[JAVA RMI (TLS)]** El Virtual Thread invoca remotamente el cálculo del `StressScore` (mediante `StressTask`) sobre el `NODO 2`. El flujo viaja por red con encriptación TLS.
8. **[JAVA NODE]** El `NODO 2` (Motor de Cálculo) ejecuta `StressTask.execute()` en un *Virtual Thread* y devuelve el StressScore.
9. **[CASSANDRA]** El cliente persiste la medición enriquecida (métricas + StressScore) en `webhardmon.mediciones`.
10. **[ACK]** Solo tras procesar y persistir el lote, el cliente confirma el *offset* con `commitSync` (**ack manual**, `enable.auto.commit=false`). Un mensaje no procesable se aparca en la **dead-letter** `telemetry.DLT` sin bloquear el flujo, garantizando *at-least-once*.


## 5. 🚀 Resiliencia (Casos Extremos)

- **Un Nodo de procesado se quema/cae:** Los demás dejarán de recibir pings. Tras 3-5 iteraciones, el nodo se elimina del Directorio Global P2P. El Cliente al fallar su intento inicial, lanza un *Failover* automático e intenta el siguiente nodo transparente. Ningún dato de Kafka se pierde.
- **Go genera 100 veces más tráfico del habitual:** Kafka se encargará del encolamiento y almacenamiento. El cluster procesará tan rápido como pueda.
- **Se incorporan más Nodos:** Simplemente arrancan un nuevo proceso instanciado apontando a la capa de Semillas (*Seeds*). En un par de *pings* el cluster entero conoce los nuevos recursos computacionales disponibles de forma orgánica.
- **El consumidor falla al procesar/persistir un mensaje:** gracias al **ack manual**, el *offset* solo se confirma tras procesar el lote, de modo que un fallo provoca el reprocesamiento (semántica *at-least-once*) y ningún mensaje se pierde. Un mensaje irrecuperable (p.ej. corrupto) se desvía a la **dead-letter** `telemetry.DLT` con cabeceras de diagnóstico, sin bloquear el resto del flujo.
- **Un collector con licencia revocada o falsa intenta inyectar datos:** NiFi lo rechaza en el gateway con **HTTP 401** verificando `licencia.activa` contra MySQL, antes de tocar Kafka. La revocación (`activa = 0`) surte efecto en el siguiente envío sin reiniciar nada.

---

## 6. 📈 Evaluación Cuantitativa del Rendimiento y Escalabilidad

Para validar científicamente las decisiones de diseño adoptadas, se ha implementado un componente de benchmark (`cluster.Benchmark`) en el submódulo del Cliente para evaluar tanto la concurrencia a nivel de nodo individual como la escalabilidad horizontal en un entorno distribuido de nodos variables.

### 6.1. Pruebas de Concurrencia de un Nodo
Se ejecutó un lote estresante de **500 tareas simultáneas** simulando el cálculo de la métrica **Stress Score** junto con operaciones bloqueantes de red o almacenamiento (esperas de 15ms) bajo tres estrategias de concurrencia:

1. **Secuencial (Bloqueante)**: 
   - **Tiempo total**: **10.294 ms**
   - **Rendimiento**: **48,6 tareas/seg**
   - **Limitación**: El hilo principal se bloquea durante la espera de red/disco de cada tarea, forzando un procesamiento puramente secuencial.
2. **Hilos de Plataforma (Pool Fijo de 20)**:
   - **Tiempo total**: **731 ms (Speedup: 14,08x)**
   - **Rendimiento**: **684,0 tareas/seg**
   - **Limitación**: Mejora sustancial al procesar hasta 20 tareas en paralelo, pero genera cola de espera en el pool al haber más tareas concurrentes (500) que hilos de CPU asignados. Aumentar el pool a 500 hilos dispararía el consumo de memoria JVM (~1MB por pila de hilo).
3. **Hilos Virtuales (Project Loom - Java 21)**:
   - **Tiempo total**: **150 ms (Speedup: 68,63x)**
   - **Rendimiento**: **3.333,3 tareas/seg**
   - **Ventaja**: Los hilos virtuales se suspenden y reanudan de forma transparente sin bloquear el hilo físico del sistema operativo cuando esperan I/O. Esto maximiza la eficiencia de CPU y permite que miles de tareas concurrentes se resuelvan casi instantáneamente con un único hilo portador de OS activo.

#### 🧪 Cómo y qué se ha probado en Concurrencia (Detalle de la prueba):
- **Clase de ejecución**: [`Benchmark.java`](file:///c:/Users/aimar/Downloads/files/client/src/main/java/cluster/Benchmark.java) en el submódulo `client` (método `runLocalBenchmark()`).
- **Lógica interna de simulación (Modelo a Trozos / Piecewise)**:
  - Cada una de las 500 tareas simula el procesamiento de una ventana temporal de **1.000 muestras de telemetría** generadas con ruido gaussiano (fluctuaciones realistas de CPU, RAM, Disco y Temperatura).
  - Para cada muestra calcula el **Stress Score** aplicando el **Modelo a Trozos**: media ponderada `(0.35·CPU + 0.30·Temp + 0.20·RAM + 0.15·Disco)` con **Hard Override** cuando `max(CPU, Temp) ≥ 90%`, siguiendo la metodología USE de Brendan Gregg.
  - Aplica suavizado **EWMA** (Exponential Weighted Moving Average, α=0.3) sobre la serie temporal de scores.
  - Calcula estadísticas (media, desviación típica) y percentiles **P95/P99** mediante ordenación `Arrays.sort()` (complejidad O(n log n)).
  - Finalmente ejecuta `Thread.sleep(15)` simulando la persistencia del resultado en BD (operación de E/S bloqueante de 15ms).
- **Proceso de ejecución**:
  1. Compila el proyecto completo desde el directorio raíz:
     ```bash
     mvn clean package -DskipTests
     ```
  2. Ejecuta el benchmark de concurrencia local (sin argumentos):
     ```bash
     java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Benchmark
     ```
  3. El programa ejecuta secuencialmente las 500 tareas (acumulando el tiempo de bloqueo en un único hilo), luego las distribuye en un `ThreadPoolExecutor` fijo de 20 hilos de plataforma tradicionales, y finalmente usa `Executors.newVirtualThreadPerTaskExecutor()` para lanzarlas concurrentemente mediante hilos virtuales. Al finalizar, vuelca en la consola una tabla comparativa con los tiempos y tasas de rendimiento obtenidos.

---

### 6.2. Escalabilidad del Clúster con Nodos Variables
Se evaluó el comportamiento global del sistema distribuyendo **150 tareas de alta computación en CPU y bloqueo I/O** por Round-Robin hacia un número incremental de nodos activos:

- **1 Nodo Activo**: **138,89 tareas/seg** (Tiempo: 1.080 ms)
- **2 Nodos Activos**: **261,78 tareas/seg** (Tiempo: 573 ms)
- **3 Nodos Activos**: **537,63 tareas/seg** (Tiempo: 279 ms)

#### Análisis de Escalabilidad:
1. **Desacoplamiento y Carga Lineal**: El balanceador del Cliente reparte la carga de trabajo de forma dinámica usando Round-Robin. 
2. **Escalabilidad Horizontal Real**: Ejecutar múltiples nodos de cálculo en la misma máquina física para este benchmark local comparte los cores reales de CPU y el ancho de banda del bucle local de red (localhost), limitando el factor de aceleración máximo. Sin embargo, en un entorno de red distribuido real con máquinas físicas dedicadas por nodo, la adición de nodos incrementa linealmente la capacidad del clúster de forma proporcional al número de CPUs reales.
3. **Diseño Modulado sin Coordinador Central**: La arquitectura Gossip permite expandir la potencia del clúster de forma caliente (on-the-fly) sin cuellos de botella de sincronización centralizada.

#### 🧪 Cómo y qué se ha probado en Escalabilidad (Detalle de la prueba):
- **Clases de ejecución**: [`Benchmark.java`](file:///c:/Users/aimar/Downloads/files/client/src/main/java/cluster/Benchmark.java) (método `runClusterBenchmark()`) enviando instancias de la clase serializable [`BenchmarkTask.java`](file:///c:/Users/aimar/Downloads/files/common/src/main/java/cluster/BenchmarkTask.java) al clúster de servidores [`ClusterNode.java`](file:///c:/Users/aimar/Downloads/files/server/src/main/java/cluster/ClusterNode.java).
- **Lógica interna de simulación (`BenchmarkTask`) — Pipeline completo**:
  - Cada una de las 150 tareas distribuidas ejecuta el pipeline de análisis de telemetría sobre una ventana de **5.000 muestras** generadas con distribución gaussiana sobre las métricas base.
  - Calcula el **Stress Score** para cada muestra usando el **Modelo a Trozos** (media ponderada + Hard Override al 90%), aplica suavizado **EWMA** (α=0.3), obtiene estadísticas descriptivas y percentiles **P95/P99** mediante ordenación.
  - Realiza **detección de anomalías por Z-Score** (umbral > 2σ) y calcula un score final compuesto ponderando los estadísticos con un factor multiplicativo por anomalías detectadas.
  - Luego, ejecuta un bloqueo `Thread.sleep(40)` (40ms) que emula la latencia de almacenamiento distribuido e I/O de red.
- **Proceso de ejecución**:
  1. Asegúrate de tener Kafka iniciado (`docker compose up -d kafka`).
  2. Levanta entre 1 y 3 nodos de cálculo en terminales independientes:
     - **Terminal del Nodo 1**:
       ```bash
       java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102
       ```
     - **Terminal del Nodo 2**:
       ```bash
       java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102
       ```
     - **Terminal del Nodo 3**:
       ```bash
       java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102
       ```
  3. Ejecuta la herramienta de benchmark pasándole el parámetro `cluster` y la lista de direcciones de los nodos activos que deseas evaluar:
     - **Para probar con 1 Nodo activo**:
       ```bash
       java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Benchmark cluster localhost:6100
       ```
     - **Para probar con 2 Nodos activos**:
       ```bash
       java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Benchmark cluster localhost:6100 localhost:6101
       ```
     - **Para probar con 3 Nodos activos**:
       ```bash
       java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Benchmark cluster localhost:6100 localhost:6101 localhost:6102
       ```
  4. El programa despacha en paralelo las 150 tareas desde el cliente mediante un pool de hilos virtuales balanceándolas uniformemente en Round-Robin entre los stubs RMI disponibles. Mide el tiempo total en milisegundos que le toma procesar el lote completo bajo cada topología de clúster incremental y calcula el rendimiento (tareas/seg).

---

## 7. 📦 Despliegue y Operación

El sistema se empaqueta en contenedores y se despliega por **capas de Docker Compose**, lo que permite levantar solo el núcleo de procesamiento o el stack completo con ingesta.

### 7.1. Imágenes
| Imagen | Rol | Réplicas |
|---|---|---|
| `rmi-server` | Nodo del clúster RMI (calcula StressScore). Clúster P2P con gossip. | 3 (1 por VM) |
| `rmi-client` | Puente: consume Kafka (Avro/SR) → StressScore por RMI → escribe Cassandra. | 1 |

Se construyen desde `server/Dockerfile` y `client/Dockerfile` y se publican en **Harbor** (local) o **Artifact Registry** (GCP). Detalle del contrato de integración en `CONTAINER_CONTRACT.md`.

### 7.2. Compose por capas
- **`docker-compose.yml` (core):** Kafka + Schema Registry + Cassandra (con `cassandra-init` que carga `db/schema-cassandra.cql`) + 3 nodos `rmi-server` + `rmi-client`. Es un entorno end-to-end de procesamiento sin necesidad de la infra del equipo.
- **`docker-compose.ingest.yml` (overlay de ingesta):** añade **NiFi** + **MySQL** (inicializado con `db/init.sql`). Se fusiona con el core compartiendo red:
  ```bash
  docker compose -f docker-compose.yml -f docker-compose.ingest.yml up -d
  ```
- **`docker-compose.gcp.yml` (producción):** core de producción en GCP; admite el mismo overlay de ingesta con `-f`.

### 7.3. Nube y orquestación
- **GCP turnkey:** `deploy-gcp.sh` automatiza el despliegue en Google Cloud (ver `DEPLOY_GCP.md`).
- **Kubernetes:** `k8s-deployment.yaml` describe el despliegue en clúster K8s.
- **Infraestructura del equipo:** repositorio OpenTofu **`webhardmon-infra`** que provisiona **3 nubes** (Proxmox local + GCP-A + GCP-B) interconectadas por una malla **WireGuard**, con **Cloudflare Tunnel** para exposición y registries **Harbor** + **Artifact Registry**.
  > ⚠️ **RMI sobre WireGuard:** los `HOST`/`SEEDS`/`java.rmi.server.hostname` deben ser nombres/IPs del *WireGuard*, no de Docker, o los stubs RMI apuntarán a direcciones inalcanzables entre VMs.

### 7.4. Integración Continua (CI)
- **SonarCloud:** `.github/workflows/sonar.yml` analiza el monorepo multi-lenguaje (**Java 21** de los 3 módulos Maven + **Go** del agente). Configuración en `sonar-project.properties`.

### 7.5. Ficheros clave del proyecto
| Fichero | Propósito |
|---|---|
| `schemas/telemetry.avsc` | Esquema Avro del topic `telemetry` (subject `telemetry-value`). |
| `db/schema-cassandra.cql` | Keyspace `webhardmon` y tablas (`mediciones`, `ordenadores`, `empresas`). |
| `db/init.sql` | Esquema + seed de la BD de la aplicación (MySQL). |
| `NIFI_GATEWAY.md` | Guía del gateway de ingesta NiFi y la verificación de licencia. |
| `CONTAINER_CONTRACT.md` | Contrato de despliegue de las imágenes `rmi-server`/`rmi-client`. |