# 📊 Proyecto: Cluster RMI sin Punto Único de Fallo

## 🎯 ¿Qué es este proyecto?

Es un **sistema de cluster distribuido** hecho en Java RMI donde múltiples nodos pueden comunicarse entre sí de forma peer-to-peer (de igual a igual) **sin necesidad de un servidor central**. El cliente puede conectarse a cualquier nodo, y si falla uno, automáticamente se conecta a otro.

Es como una red de computadoras donde cada una es independiente, pero todas se conocen entre sí y trabajan juntas.

---

## 🏗️ Arquitectura del Sistema

```
┌─────────────────────────────────────────────────────────────┐
│                         CLIENTE                             │
│  (Bootstrap → Round-Robin → Failover)                      │
└─────────────────────────────────────────────────────────────┘
                          ↓ ↓ ↓
        ┌─────────────────┼─────────────────┐
        ↓                 ↓                 ↓
   ┌─────────┐       ┌─────────┐       ┌─────────┐
   │  NODE 1 │◄─────►│  NODE 2 │◄─────►│  NODE 3 │
   │ (Gossip)│       │ (Gossip)│       │ (Gossip)│
   └─────────┘       └─────────┘       └─────────┘
     RMI:6100         RMI:6101         RMI:6102
```

### Características clave:

1. **Sin punto único de fallo**: No hay servidor central. Cada nodo es independiente.
2. **RMI (Java Remote Method Invocation)**: Comunicación remota entre máquinas Java.
3. **Descubrimiento automático**: Los nodos se conocen entre sí mediante gossip (chismes).
4. **Resiliencia**: Si un nodo cae, los demás siguen funcionando.
5. **Bootstrap desde semillas**: El cliente solo necesita saber la dirección de al menos un nodo para empezar.

---

## 📁 Explicación de Cada Archivo

### 1. **Endpoint.java** 🗺️
**¿Qué es?** Una clase que representa la identidad de red de un nodo.

```
┌──────────────────┐
│ Endpoint         │
├──────────────────┤
│ nodeId: String   │  <- Identificador único del nodo
│ host: String     │  <- IP o hostname (ej: localhost)
│ port: int        │  <- Puerto RMI (ej: 6100)
└──────────────────┘
```

**Funciones principales:**
- `parse("localhost:6100")`: Convierte un string en un Endpoint
- `addr()`: Devuelve "host:port" como clave única
- Implementa `Serializable` para poder enviarse por RMI

**Analogy**: Es como el DNI de una persona, pero para un nodo del cluster.

---

### 2. **ComputeService.java** 📡
**¿Qué es?** Una interfaz remota que define qué operaciones puede hacer cada nodo.

```java
Interface ComputeService (Remote)
├── getNodeId() → String
├── ping() → long (prueba que esté vivo)
├── executeTask(Task<T>) → T (motor de computación genérico)
├── processTelemetry(byte[]) → void (procesamiento ETL de Avro)
└── gossip(sender, theirView) → Set<Endpoint> (descubrimiento)
```

**¿Por qué Remote?** Para que otros programas Java puedan llamar estos métodos a través de la red.

**¿Qué hace cada método?**
- `executeTask(Task<T>)`: Ejecuta una tarea genérica enviada por el cliente (Compute Engine).
- `processTelemetry(byte[])`: Procesa métricas en formato Avro a través de Virtual Threads, calculando el Stress Score.
- `gossip(...)`: Intercambia información sobre qué nodos existen (protocolo anti-entropy).
- `ping()`: Verifica que el nodo siga vivo.

---

### 3. **ClusterNode.java** 🖥️
**¿Qué es?** Un nodo del cluster. Es el "servidor" que:
- Crea su propio registro RMI
- Escucha en un puerto específico
- Se comunica con otros nodos via gossip
- Ejecuta tareas (compute)

**Estructura interna:**

```
┌────────────────────────────────────────┐
│         ClusterNode (Nodo)             │
├────────────────────────────────────────┤
│ self: Endpoint                         │ ← Quién soy
│ seeds: List<Endpoint>                  │ ← Nodos de arranque
│ members: Map<String, Endpoint>         │ ← Nodos que conozco
│ failures: Map<String, Int>             │ ← Fallos de cada nodo
│ tombstones: Map<String, Long>          │ ← Nodos muertos (temp)
├────────────────────────────────────────┤
│ Ejecuta cada 3 segundos:               │
│ - Intenta gossip con cada miembro      │
│ - Cuenta fallos (max 3)                │
│ - Evicta si fallos >= 3                │
│ - Crea tombstone por 30 segundos       │
└────────────────────────────────────────┘
```

**¿Qué es el Gossip?**
Es como si los nodos chismearan entre sí:
- "Hola, soy NODE-1 y conozco a NODE-2 y NODE-3"
- "OK, actualicé mi lista de miembros conocidos"
- Si no responde 3 veces → Lo marcamos como muerto (tombstone)
- Si se reinicia → El tombstone se borra automáticamente

**¿Qué es un Tombstone?**
Una marca que dice "este nodo estaba muerto". Evita que otros nodos sigan intentando conectar a una dirección que ya no existe.

---

### 4. **Client.java** 👤
**¿Qué es?** El programa que USA el cluster. Es quien pide operaciones.

**Flujo de trabajo:**

```
1. Bootstrap
   ├─ "Dame la lista de nodos conocidos"
   └─ Solo necesita 1 nodo respondiendo

2. Cache + Round-Robin
   ├─ Guarda lista de miembros
   ├─ Alterna entre ellos (NODE-1 → NODE-2 → NODE-3 → NODE-1)
   └─ Esto balancea carga

3. Failover (si falla)
   ├─ Intenta siguiente nodo
   └─ Si todos fallan → Refresca lista y reintenta

4. Ejecuta tarea
   └─ processTelemetry(valor) en cualquier nodo disponible
```

**¿Qué simula el cliente?**
- Un puente (consumidor) que recibe datos desde RabbitMQ y los pasa al cluster.
- En el `main()` se conecta a RabbitMQ (usando Virtual Threads) y consume de la cola `telemetry_java_consumer_queue`.
- Por cada mensaje Avro recibido, hace: `processTelemetry(payload)` a un nodo del cluster.
- Simula: **Ingesta asíncrona, balanceo de carga, recuperación de fallos, y descubrimiento dinámico**.

---

## 🔄 Flujo Completo: Paso a Paso

### Escenario: Cliente quiere procesar telemetría `processTelemetry(payload)`

```
┌─────────────────────────────────────────────────────────────┐
│ CLIENT (Consumer de RabbitMQ)                                │
└─────────────────────────────────────────────────────────────┘
                              │
                    ¿Conozco miembros?
                     │         │
                    NO        SÍ
                     │         │
                [REFRESH]   [CACHE]
                     │         │
              Llamo a seed    Intento nodo siguiente
              (NODE-1)        (NODE-2)
                     │         │
              "Dame lista"  "Procesa telemetría (Avro)"
                     │         │
              ✓ Responde    ✓ Responde y procesa ETL
                     │         │
             Guardo lista    Ack a RabbitMQ
             [members]        
                     │
            Siguiente mensaje
            round-robin
            (NODE-3)
```

---

## 📊 Escenarios de Fallos y Recuperación

### Escenario 1: Un nodo se cae

```
Antes:  NODE-1 ✓  NODE-2 ✓  NODE-3 ✓
                    ↓ (cae)
Después: NODE-1 ✓  NODE-2 ✗  NODE-3 ✓

Cliente:
- Intenta NODE-2: FALLA ❌
- Automáticamente intenta NODE-3: OK ✓
- El cliente NO se da cuenta del fallo (transparente)
- Gossip marca NODE-2 con tombstone por 30s
```

### Escenario 2: Partición de red (cliente aislado)

```
Antes:  [CLIENT] ←→ NODE-1 ✓
               ↓ (corte de red)
Después: [CLIENT] ✗ NODE-1 ✓

Cliente intenta processTelemetry():
- Intenta NODE-1: TIMEOUT ❌
- Refresca lista de miembros: FALLA (sin conexión)
- Lanza excepción: "All members failed even after a refresh"
```

### Escenario 3: Nodo se reinicia

```
NODE-1: MUERTO (tombstone 30s) ✓
           ↓ (se reinicia)
NODE-1: VIVO (en la misma dirección IP:puerto)

Gossip recibe:
- contacto directo de NODE-1 → Borra tombstone INMEDIATAMENTE
- NODE-1 se reúne al cluster sin esperar 30s
```

---

## ⚙️ Configuración y Parámetros Clave

| Parámetro | Valor | Significado |
|-----------|-------|-------------|
| `SERVICE_NAME` | `"ComputeNode"` | Nombre bajo el cual se registra en RMI |
| `MAX_FAILURES` | `3` | Intentos de gossip fallidos antes de evictar |
| `TOMBSTONE_TTL_MS` | `30,000` | 30 segundos que dura la marca de "muerto" |
| `GOSSIP_INTERVAL_S` | `3` | Cada 3 segundos se ejecuta gossip |
| Default RMI port | `6100+` | Cada nodo usa su puerto |

---

## 🚀 ¿Cómo se ejecuta?

### Terminal 1: Inicia NODE-1
```bash
java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102
```

### Terminal 2: Inicia NODE-2
```bash
java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102
```

### Terminal 3: Inicia NODE-3
```bash
java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102
```

### Terminal 4: Ejecuta el cliente
```bash
java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Client localhost:6100 localhost:6101 localhost:6102
```

---

## 🎓 Preguntas Frecuentes

### P1: ¿Por qué necesita "semillas" (seeds)?
**R:** El bootstrap es el problema. Cuando un cliente arranca, no conoce a ningún nodo.
Las semillas son direcciones conocidas del cluster que dice "prueba en estas direcciones".
Si al menos UNA responde, el cliente obtiene la lista completa de todos.

### P2: ¿Qué es RMI?
**R:** Remote Method Invocation. Permite llamar métodos en objetos de otras máquinas Java como si estuvieran locales.
```java
// En máquina remota
Registry reg = LocateRegistry.getRegistry("localhost", 6100);
ComputeService stub = (ComputeService) reg.lookup("ComputeNode");
stub.processTelemetry(payload);  // ¡Llamada remota!
```

### P3: ¿Qué pasa si TODOS los nodos se caen?
**R:** El cluster está muerto. El cliente lanzará excepción.
Pero si se reinician, se recuperan automáticamente porque cada nodo re-añade sus semillas.

### P4: ¿Cómo detecta que un nodo está muerto?
**R:** Por timeout de RMI (3 fallos = ~9 segundos de silencio) + gossip periódico.
No es un heartbeat explícito, sino por falta de respuesta en gossip.

### P5: ¿Por qué se llama "anti-entropy" al gossip?
**R:** Porque el gossip reduce la entropía (desorden) del sistema.
Todos los nodos gradualmente convergen a la misma vista de membresía.

### P6: ¿Qué es "round-robin"?
**R:** Una técnica de balanceo: el cliente alterna entre nodos en orden circular.
```
Llamada 1 → NODE-1
Llamada 2 → NODE-2
Llamada 3 → NODE-3
Llamada 4 → NODE-1 (vuelta al inicio)
```

### P7: ¿Puede haber "split-brain" (dos clusters separados)?
**R:** SÍ. Si la red se particiona, cada parte funciona independientemente.
Cuando se reconecta, habrá un período de convergencia. Este diseño NO resuelve split-brain.

### P8: ¿Por qué los tombstones duran 30 segundos?
**R:** Balance entre:
- **Corto**: Rejoin rápido si reinicia
- **Largo**: Evita re-introducir nodos muertos por un tiempo

---

## 📈 Ventajas y Limitaciones

### ✅ Ventajas
- **Sin punto único de fallo** (SPOF)
- **Escalable**: Agregar nodos es trivial
- **Resiliente**: Aguanta caídas parciales
- **Simple**: Gossip es elegante y probado (BitTorrent, Cassandra, etc.)

### ❌ Limitaciones
- **Sin garantías de consistencia** (eventual consistency)
- **No resuelve split-brain** (particiones de red)
- **RMI es lento** (Java puro, sin protobuf/gRPC)
- **Encriptación vía TLS**: Implementada vía properties JVM (ver `RUN_WITH_TLS.md`)
- **Gossip periódico**: Detección de fallos toma tiempo

---

## 🔍 Flujo de Código: Cliente → Nodo

```
CLIENT.processTelemetry(payload)
  ↓
¿members está vacío? → SÍ: refresh() → GOSSIP con seed
  ↓ NO
Selecciona nodo con round-robin (cursor)
  ↓
RMI: stub.processTelemetry(payload)  [TIMEOUT/EXCEPTION]
  ↓
¿Hay más nodos? → SÍ: Intenta siguiente
                 NO: refresh() + retry
  ↓
✓ Devuelve sqrt(1.5) * 2 = 2.449
```

---

## 💡 Caso de Uso Real

Imagina un sistema de **procesamiento de imágenes distribuido**:

```
┌──────────────────┐
│  Servidor Web    │ (Cliente)
│  (recibe fotos)  │
└──────────────────┘
         ↓
    processTelemetry(foto)
         ↓
    ┌──────┬──────┬──────┐
    ↓      ↓      ↓      ↓
 [NODE-1] [NODE-2] [NODE-3] (Procesan en paralelo)
   Filter  Resize  Compress
    ↓      ↓      ↓
    └──────┬──────┘
         ↓
    Foto procesada
    (devuelve a web)
```

Si se cae NODE-2, la web sigue funcionando usando NODE-1 y NODE-3.

---

## 📚 Resumen Visual

```
┌─────────────────────────────────────────────────────┐
│ CLUSTER RMI - ARQUITECTURA GENERAL                 │
├─────────────────────────────────────────────────────┤
│                                                     │
│  Componentes:                                      │
│  1. Endpoint     → Identidad de red (IP:puerto)   │
│  2. ComputeService → Interfaz remota              │
│  3. ClusterNode  → Servidor con gossip             │
│  4. Client       → Consumidor de servicios         │
│                                                    │
│  Mecanismos:                                       │
│  • RMI Registry     → Directorio de servicios     │
│  • Gossip Protocol  → Descubrimiento de nodos     │
│  • Tombstones       → Marca de nodos muertos      │
│  • Round-robin      → Balanceo de carga           │
│  • Failover         → Recuperación automática      │
│                                                    │
└─────────────────────────────────────────────────────┘
```

---

## 🎬 Conclusión

Este proyecto es un **ejemplo de arquitectura distribuida sin SPOF**.
- **Real world**: Cassandra, Dynamo, Consul usan similares
- **Teaching**: Perfecta para aprender clustering
- **Resilience**: Explica por qué los sistemas nube son redundantes

¡El cliente simula un usuario/aplicación que necesita ejecutar tareas en un cluster donde cualquier nodo puede fallar en cualquier momento!
