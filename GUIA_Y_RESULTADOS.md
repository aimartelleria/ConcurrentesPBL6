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
* **Interpretación**: 
  - **Evicción**: Los nodos del cluster detectaron que `node-2` ya no responde a los pings de Gossip y lo eliminaron de la vista de miembros de forma descentralizada.
  - **Failover automático**: El Cliente Java detectó la desconexión del nodo asignado en su cola Round-Robin, reintentó la petición con el siguiente nodo vivo (`node-1`) y procesó el mensaje sin pérdida de información.
