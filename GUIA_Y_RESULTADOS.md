# 📖 Guía de Ejecución y Resultados del Cluster

Este documento explica cómo funciona el flujo de datos entre el agente de monitoreo (Go), la cola de mensajería (RabbitMQ) y la malla de procesamiento distribuido (Java RMI), y detalla cómo interpretar los resultados obtenidos en las pruebas de ejecución y failover.

---

## 🏗️ Flujo de Datos

```text
┌────────────────┐          ┌──────────────┐          ┌──────────────┐
│   Agente Go    │ ──Avro──►│   RabbitMQ   │ ──AMQP──►│ Cliente Java │
│ (Métricas CPU/ │          │   (Broker)   │          │ (RMI Bridge) │
│  RAM/Disk/Temp)│          └──────────────┘          └──────┬───────┘
└────────────────┘                                           │
                                                  Balanceo / │ RMI
                                                    Failover │
                                                             ▼
                                                    ┌────────────────┐
                                                    │  Cluster Java  │
                                                    │ (Virtual Th.)  │
                                                    └────────────────┘
```

1. **Agente Go**: Inspecciona el sistema operativo usando `gopsutil` en intervalos regulares, genera un registro binario serializado con el esquema **Avro**, y lo publica en un exchange tipo fanout (`telemetry_fanout`) de RabbitMQ.
2. **Cliente Java**: Está permanentemente suscrito al exchange de RabbitMQ. En cuanto recibe una métrica binaria en formato Avro, actúa como un puente ("bridge") y la despacha al cluster Java utilizando llamadas RMI.
3. **Nodos del Cluster Java (`ClusterNode`)**:
   - Cada nodo hospeda su propio registro RMI de forma distribuida (sin un servidor central).
   - Utilizan un protocolo de **Gossip (chisme)** periódico en segundo plano para informarse mutuamente qué nodos están vivos o cuáles han caído (evicción).
   - Al recibir el paquete Avro, realizan un proceso de enriquecimiento (ETL) y calculan un **Stress Score** en paralelo mediante **Virtual Threads**.

---

## 🛠️ Ajustes Realizados para la Integración

1. **Exchange Unificado**: Se modificó `SuscriberRabbitMQ.go` para que publique datos en el exchange de tipo fanout `telemetry_fanout`, sincronizándolo con la cola que escucha el `Client.java`.
2. **Compatibilidad de Compilación**: Se redujo el requerimiento de versión en `go.mod` (`go 1.22+`) y se ejecutó `go mod tidy` para garantizar que compile sin errores en entornos estándares.

---

## 🚀 Cómo iniciar el sistema en local

### 1. Iniciar RabbitMQ
Levantar el broker en Docker:
```bash
docker run -d --name rabbitmq-cluster -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

### 2. Arrancar los Nodos Java (Cluster)
Compila el proyecto Java:
```bash
mvn clean package
```
Inicia tres nodos en distintas terminales usando diferentes puertos RMI:
```bash
# Terminal 1 (Nodo 1)
java -cp "server/target/server-1.0-SNAPSHOT.jar" cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102

# Terminal 2 (Nodo 2)
java -cp "server/target/server-1.0-SNAPSHOT.jar" cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102

# Terminal 3 (Nodo 3)
java -cp "server/target/server-1.0-SNAPSHOT.jar" cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102
```

### 3. Arrancar el Cliente Java (Consumidor/Puente)
```bash
java -cp "client/target/client-1.0-SNAPSHOT.jar" cluster.Client localhost:6100 localhost:6101 localhost:6102
```

### 4. Ejecutar el Agente Go
```bash
go run AgenteGo-main/SuscriberRabbitMQ.go -rabbitmq-url amqp://guest:guest@localhost:5672/
```

---

## 📊 Interpretación de Resultados de Prueba

### Envío de Métricas Normales (Go)
El Agente Go recopila las métricas de hardware del sistema:
```text
Iniciando servicio de recolección métricas en segundo plano...
Se enviarán métricas a RabbitMQ cada 5 minutos.
[2026-05-20 08:42:58] Datos recolectados normales: {Timestamp:1779266578 CPUPercent:1.182327318355726 CPUModel:12th Gen Intel(R) Core(TM) i7-12650H RAMPercent:23.011672656562755 RAMTotal:8175046656 DiskPercent:9.89245871010225 DiskTotal:1081101176832 Temp:<nil>}
[2026-05-20 08:42:58] datos binarios enviados correctamente por RabbitMQ
```
* **Interpretación**: Indica que el recolector de Go accedió al hardware del host (o contenedor), serializó la información en formato Avro binario (~78 bytes debido a las cadenas y longs adicionales) y la encoló exitosamente en RabbitMQ.

### Procesamiento y Enriquecimiento (Java)
El nodo que recibe la llamada RMI ejecuta el pipeline ETL y visualiza la información formateada:
```text
[node-2 | Thread: ] ETL Procesado.
  - CPU Model: 12th Gen Intel(R) Core(TM) i7-12650H (1,18%)
  - RAM Total: 7,61 GB (23,01%)
  - Disk Total: 1006,85 GB (9,89%)
  - Temp: N/A
  - Stress Score: 11,66 / 100
```
* **Interpretación**: 
  - La llamada RMI funcionó correctamente y deserializó el registro Avro completo.
  - Las capacidades de memoria y disco se convirtieron de bytes a Gigabytes (GB) de forma legible para el operador (`7,61 GB` y `1006,85 GB`).
  - La temperatura se muestra como `N/A` porque el agente de Go se ejecuta dentro de un contenedor virtualizado (Docker Desktop en Windows) donde la interfaz térmica de Linux no está disponible.
  - El **Stress Score** de `11,66 / 100` se calculó usando la ponderación: `(1,18% * 0.4) + (23,01% * 0.4) + (9,89% * 0.2)`. 

### Comportamiento ante Caídas (Failover RMI)
Cuando apagamos `node-2` e iniciamos otro envío desde el agente de Go, el cliente Java muestra:
```text
[x] Received Avro Telemetry (38 bytes)
node-2@localhost:6101 failed (ConnectException), trying next...
-> dispatching telemetry to node-1@localhost:6100
```
Y en los logs del cluster se reporta:
```text
Evicted (no response after 3 rounds): node-2@localhost:6101
[node-1] view: [node-3@localhost:6102]
```
  - **Evicción**: Los nodos del cluster detectaron que `node-2` ya no responde a los pings de Gossip y lo eliminaron de la vista de miembros de forma descentralizada.
  - **Failover automático**: El Cliente Java detectó la desconexión del nodo asignado en su cola Round-Robin, reintentó la petición con el siguiente nodo vivo (`node-1`) y procesó el mensaje sin pérdida de información.

---

## 📈 Pruebas de Rendimiento y Justificación Cuantitativa

Para cumplir con los requisitos del proyecto, se ha desarrollado un banco de pruebas (`cluster.Benchmark`) que permite ejecutar y analizar comparativamente las estrategias de paralelización a nivel de nodo y a nivel de clúster.

### 1. Benchmark de Concurrencia (Nivel de Nodo)
Esta prueba evalúa tres estrategias diferentes ejecutando **500 tareas simultáneas** que simulan el cálculo del **Stress Score** y un pipeline ETL ligero, seguido de una espera bloqueante de I/O de 15ms (escrituras simuladas en disco/BD):

| Estrategia de Concurrencia | Tiempo Total (ms) | Rendimiento (tareas/seg) | Factor de Aceleración (Speedup) | Hilos Físicos de OS Activos |
| :--- | :---: | :---: | :---: | :---: |
| **Secuencial (Bloqueante)** | 10.294 ms | 48,6 tareas/seg | 1,00x (Base) | 1 |
| **Hilos de Plataforma (Pool=20)** | 731 ms | 684,0 tareas/seg | 14,08x | 20 (fijo) |
| **Hilos Virtuales (Java 21 Loom)** | **150 ms** | **3.333,3 tareas/seg** | **68,63x** | **1** (Portador) |

#### 🧠 Justificación Cuantitativa de Hilos Virtuales:
- **Evitación del Bloqueo**: Con Hilos Virtuales, cuando la tarea se bloquea en la llamada de I/O (`Thread.sleep(15)`), el hilo virtual se "desmonta" del hilo portador físico del sistema operativo, permitiendo que otro hilo virtual use ese procesador. Esto elimina la penalización por cambio de contexto y el bloqueo físico de recursos.
- **Eficiencia en Recursos**: En lugar de requerir 500 hilos de plataforma (que consumirían ~500MB de memoria en pilas de hilos), los hilos virtuales ocupan apenas unos pocos kilobytes cada uno en el heap, permitiendo concurrencia masiva con un consumo mínimo de RAM y solo 1 hilo portador activo de OS.

---

### 2. Benchmark de Escalabilidad (Nivel de Clúster de Nodos Variables)
Esta prueba distribuye **150 tareas del pipeline de Stress Score (Modelo a Trozos + EWMA + P95/P99 + Z-Score + I/O)** en Round-Robin a través de RMI hacia un número variable de nodos de cálculo (`ClusterNode`) activos simultáneamente:

| Configuración del Clúster | Tiempo Total (ms) | Rendimiento Global (tareas/seg) | Comportamiento frente a Carga |
| :--- | :---: | :---: | :--- |
| **1 Nodo Activo** | 1.080 ms | 138,89 tareas/seg | Cuello de botella físico (todo el procesamiento secuenciado en un único nodo) |
| **2 Nodos Activos** | 573 ms | 261,78 tareas/seg | Distribución activa de carga round-robin entre 2 nodos, mejorando el rendimiento |
| **3 Nodos Activos** | 279 ms | 537,63 tareas/seg | Rendimiento óptimo, minimización de latencia y máxima paralelización de I/O |

#### 🧠 Justificación del Diseño Escalable:
- **Balanceo Round-Robin**: Al distribuir tareas de manera uniforme mediante el RMI Client, evitamos que un solo nodo actúe como cuello de botella.
- **Escalabilidad Horizontal**: Si bien en un benchmark local sobre una misma máquina física la aceleración se ve limitada por compartir la CPU y la interfaz de red *loopback* local, el diseño modular desacoplado del clúster garantiza que al desplegar nodos en **máquinas físicas diferentes**, la capacidad de cómputo del clúster escale de forma lineal al aprovechar los cores y ancho de banda independientes de cada máquina del nodo de cálculo.
- **Descubrimiento Dinámico sin SPOF**: La adición o eliminación de nodos se propaga mediante Gossip de manera autónoma, permitiendo adaptar la topología según la demanda de forma flexible y tolerante a fallos.

### 🛠️ Cómo y qué se ha probado en los Benchmarks (Detalle de las pruebas)

#### 1. Pruebas de Concurrencia de un Nodo (Local)
* **Clase de ejecución**: [`Benchmark.java`](file:///c:/Users/aimar/Downloads/files/client/src/main/java/cluster/Benchmark.java) en el submódulo `client` (método `runLocalBenchmark()`).
* **Lógica interna de simulación (Modelo a Trozos / Piecewise)**:
  * Cada una de las 500 tareas simula el procesamiento de una ventana temporal de **1.000 muestras de telemetría** generadas con ruido gaussiano (fluctuaciones realistas de CPU, RAM, Disco y Temperatura).
  * Para cada muestra calcula el **Stress Score** aplicando el **Modelo a Trozos**: media ponderada `(0.35·CPU + 0.30·Temp + 0.20·RAM + 0.15·Disco)` con **Hard Override** cuando `max(CPU, Temp) ≥ 90%`, siguiendo la metodología USE de Brendan Gregg.
  * Aplica suavizado **EWMA** (Exponential Weighted Moving Average, α=0.3) sobre la serie temporal de scores.
  * Calcula estadísticas (media, desviación típica) y percentiles **P95/P99** mediante ordenación `Arrays.sort()` (complejidad O(n log n)).
  * Finalmente ejecuta `Thread.sleep(15)` simulando la persistencia del resultado en BD (operación de E/S bloqueante de 15ms).
* **Proceso de ejecución**:
  1. Compila el proyecto completo desde el directorio raíz:
     ```bash
     mvn clean package -DskipTests
     ```
  2. Ejecuta el benchmark local (sin argumentos):
     ```bash
     java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Benchmark
     ```
  3. El programa ejecuta secuencialmente las 500 tareas (acumulando el tiempo de bloqueo en un único hilo), luego las distribuye en un pool de 20 hilos de plataforma tradicionales, y finalmente usa hilos virtuales para lanzarlas todas concurrentemente. Al terminar, imprime la tabla comparativa con los tiempos en consola.

#### 2. Pruebas de Escalabilidad del Clúster (Red RMI)
* **Clases de ejecución**: [`Benchmark.java`](file:///c:/Users/aimar/Downloads/files/client/src/main/java/cluster/Benchmark.java) (método `runClusterBenchmark()`), la tarea serializable [`BenchmarkTask.java`](file:///c:/Users/aimar/Downloads/files/common/src/main/java/cluster/BenchmarkTask.java) y el clúster de servidores [`ClusterNode.java`](file:///c:/Users/aimar/Downloads/files/server/src/main/java/cluster/ClusterNode.java).
* **Lógica interna de simulación (`BenchmarkTask`) — Pipeline completo**:
  * Cada una de las 150 tareas distribuidas ejecuta el pipeline de análisis de telemetría sobre una ventana de **5.000 muestras** generadas con distribución gaussiana sobre las métricas base.
  * Calcula el **Stress Score** para cada muestra usando el **Modelo a Trozos** (media ponderada + Hard Override al 90%), aplica suavizado **EWMA** (α=0.3), obtiene estadísticas descriptivas y percentiles **P95/P99** mediante ordenación.
  * Realiza **detección de anomalías por Z-Score** (umbral > 2σ) y calcula un score final compuesto ponderando los estadísticos con un factor multiplicativo por anomalías detectadas.
  * Luego, ejecuta un bloqueo `Thread.sleep(40)` (40ms) que emula la latencia de almacenamiento distribuido e I/O de red.
* **Proceso de ejecución**:
  1. Asegúrate de tener RabbitMQ iniciado (`docker run -d --name rabbitmq-cluster -p 5672:5672 -p 15672:15672 rabbitmq:3-management`).
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
  4. El programa despacha en paralelo las 150 tareas desde el cliente mediante un pool de hilos virtuales balanceándolas uniformemente en Round-Robin entre los stubs RMI de los nodos encendidos. Mide el tiempo total en milisegundos que toma procesar el lote bajo cada topología de clúster incremental y calcula el rendimiento (tareas/seg).
