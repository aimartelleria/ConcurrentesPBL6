# 📘 Memoria Técnica y Documentación Global del Proyecto

Este documento proporciona una visión unificada, profunda y definitiva sobre la arquitectura, los componentes y la **justificación de las decisiones tecnológicas** del proyecto de Cluster RMI P2P y Telemetría.

---

## 1. 🎯 Visión General

El sistema es una **red distribuida de computación *Peer-to-Peer* (P2P) y procesamiento de telemetría sin Punto Único de Fallo (SPOF)**. Su propósito es recoger datos de hardware (telemetría), encolarlos para evitar pérdida de datos en picos de carga, y distribuirlos a un enjambre de nodos de computación que se autodescubren mediante un protocolo *Gossip*. 

A nivel funcional, el sistema:
1. **Recopila** métricas en máquinas externas.
2. **Serializa y encola** de forma altamente eficiente.
3. **Distribuye** el trabajo o telemetría a través de una malla P2P de nodos Java.
4. **Resiste** caídas de nodos, redireccionando el tráfico automáticamente de manera distribuida.

---

## 2. 🏗️ Arquitectura del Sistema

La arquitectura está formada por varios subsistemas especializados que operan de forma desacoplada:

```text
┌────────────────┐     Avro     ┌──────────────────┐
│   Agente Go    ├─────────────►│      Kafka       │ (Topic de Ingesta)
│  (Data Source) │              │     (Broker)     │
└────────────────┘              └────────┬─────────┘
                                         │ Consumo
                                         ▼
                 ┌────────────────────────────────────────────────────────┐
                 │                      CLIENTE JAVA                      │
                 │   (Consumidor Kafka + RMI Bootstrap + Load Balancer)   │
                 └────────────────────────────────────────────────────────┘
                           │                  │                  │
                RMI / TLS  │                  │ RMI / TLS        │ RMI / TLS
                           ▼                  ▼                  ▼
                    ┌─────────┐        ┌─────────┐        ┌─────────┐
    Protocolo       │  NODO 1 │◄──────►│  NODO 2 │◄──────►│  NODO 3 │
    Gossip P2P      │(Compute)│        │(Compute)│        │(Compute)│
    (Membresía)     └─────────┘        └─────────┘        └─────────┘
```

### Componentes Clave:
1. **Source (Agente Go):** Obtiene datos del sistema operativo y genera cargas útiles (*payloads*).
2. **Buffer (Kafka):** Desacopla la emisión de datos de la capacidad y velocidad de procesamiento del cluster de Java.
3. **Edge / Entrypoint (Cliente Java):** Actúa como un puente. Escucha mensajes de Kafka y los balancea (Round-Robin tolerante a fallos) enviándolos por RMI al cluster.
4. **Compute Grid (Servidores ClusterNode):** Malla de nodos que ejecutan el motor de cálculo genérico (`Task`) o procesan telemetría localmente. Mantienen su estado (quién está vivo/muerto) "cotilleando" (Gossip) entre sí.

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

### 🌟 3.6. Apache Avro
Para la codificación del mensaje de Kafka a RMI.
- **Justificación:** Frente a JSON que necesita descifrar strings, Avro comprime los datos de forma binaria apoyado en un *schema* fijo, reduciendo drásticamente (hasta en un 70%) el tamaño de banda ocupada en red y acelerando la de-serialización.

### 🌟 3.7. TLS/SSL para Cifrado
Para la comunicación en el interior del cluster.
- **Justificación:** RMI en crudo carece de seguridad y transita en texto o bytes predecibles por la red (vulnerable a *man-in-the-middle* o ejecución de comandos remota maliciosa). Inyectando `-Djavax.net.ssl.trustStore` y fábricas de SSL personalizadas, el cluster RMI pasa a ser encriptado, pudiendo interconectar zonas geográficas diferentes con seguridad.

---

## 4. 🔀 Flujo Completo y Ciclo de Vida de los Datos

Para empaquetar cómo coopera todo, este es el viaje de 1 milisegundo de tu aplicación:

1. **[GO]** Un sensor en el Agente de Go detecta que la CPU local está al 90%. Empaqueta esta información.
2. **[GO -> AVRO]** Go serializa la métrica utilizando codificación binaria *Avro* y un schema compartido.
3. **[GO -> KAFKA]** Go envía este paquete binario al topic `telemetry` en *Kafka*.
4. **[BROKER]** Kafka almacena el paquete y se asegura de que exista persistencia y replicación.
5. **[JAVA CLIENT]** El nodo `Client` ("el consumidor"), escuchando mediante un bucle de consumo en el hilo principal, recibe instantáneamente desde Kafka el paquete Avro.
6. **[JAVA CLIENT -> LOAD BALANCER]** El `Client` verifica su caché Gossip. Descubre que hay 3 nodos vivos. El puntero *round-robin* selecciona el `NODO 2` y despacha el procesamiento en un Virtual Thread.
7. **[JAVA RMI (TLS)]** El Virtual Thread invoca `stubNODO2.processTelemetry(paquete_avro)`. El flujo viaja por red con encriptación TLS.
8. **[JAVA NODE]** El `NODO 2` (Motor de Cálculo) recibe el Byte Array instanciando un *Virtual Thread*, deserializa el paquete y efectúa la lógica de transformación / cálculo o almacenamiento final.


## 5. 🚀 Resiliencia (Casos Extremos)

- **Un Nodo de procesado se quema/cae:** Los demás dejarán de recibir pings. Tras 3-5 iteraciones, el nodo se elimina del Directorio Global P2P. El Cliente al fallar su intento inicial, lanza un *Failover* automático e intenta el siguiente nodo transparente. Ningún dato de Kafka se pierde.
- **Go genera 100 veces más tráfico del habitual:** Kafka se encargará del encolamiento y almacenamiento. El cluster procesará tan rápido como pueda.
- **Se incorporan más Nodos:** Simplemente arrancan un nuevo proceso instanciado apontando a la capa de Semillas (*Seeds*). En un par de *pings* el cluster entero conoce los nuevos recursos computacionales disponibles de forma orgánica.

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