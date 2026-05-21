# 🎮 Ejemplos Prácticos y Simulaciones

## 1️⃣ Ejecución Normal (Sin Fallos)

### Terminal 1: Arrancar NODE-1
```bash
$ java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102
```

**Output esperado:**
```
[node-1] Registered ComputeNode on port 6100
[node-1] Starting gossip scheduler (interval: 3s)
[node-1] Gossip with localhost:6101...
  localhost:6101 failed (ConnectException), trying next...
[node-1] Gossip with localhost:6102...
  localhost:6102 failed (ConnectException), trying next...
[node-1] Bootstrap: Only seeds known so far: {node-1@localhost:6100}
```

### Terminal 2: Arrancar NODE-2 (3 segundos después)
```bash
$ java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102
```

**Output esperado:**
```
[node-2] Registered ComputeNode on port 6101
[node-2] Attempting to join via seed localhost:6100...
[node-2] Successfully joined cluster!
Refreshed cluster view via localhost:6100: 
  [node-1@localhost:6100, node-2@localhost:6101]
```

**Output en NODE-1:**
```
[node-1] Gossip with localhost:6101... OK ✓
Gossip response: {node-1@localhost:6100, node-2@localhost:6101}
```

### Terminal 3: Arrancar NODE-3
```bash
$ java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102
```

### Terminal 4: Ejecutar Cliente

```bash
$ java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Client localhost:6100 localhost:6101 localhost:6102
```

**Output esperado:**
```
Refreshed cluster view via localhost:6100: 
  [node-1@localhost:6100, node-2@localhost:6101, node-3@localhost:6102]

-> dispatching to node-1
   [node-1] processTelemetry(payload)
   ✓ Procesado (ETL)

-> dispatching to node-2
   [node-2] processTelemetry(payload)
   ✓ Procesado (ETL)

-> dispatching to node-3
   [node-3] processTelemetry(payload)
   ✓ Procesado (ETL)

-> dispatching to node-1
   [node-1] processTelemetry(payload)
   ✓ Procesado (ETL)

...
(repite 12 veces con round-robin)
```

---

## 2️⃣ Simulación: Node Cae Durante Ejecución

### Antes de caer:
```
CLIENT:
-> dispatching to node-1: processTelemetry(payload)   ✓ ✓ Procesado (ETL)
-> dispatching to node-2: processTelemetry(payload) ✓ ✓ Procesado (ETL)
-> dispatching to node-3: processTelemetry(payload)   ✓ ✓ Procesado (ETL)
-> dispatching to node-1: processTelemetry(payload) ✓ ✓ Procesado (ETL)
```

### Ejecutamos en Terminal 4: `Ctrl+C` en NODE-2
```bash
# Terminamos NODE-2
$ java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-2 localhost 6101 ... 
^C
(se detiene abruptamente)
```

### Output del Cliente (mientras continúa):
```bash
-> dispatching to node-2: processTelemetry(payload)
   node-2@localhost:6101 failed (ConnectException), trying next...

-> dispatching to node-3: processTelemetry(payload)
   [node-3] processTelemetry(payload)
   ✓ ✓ Procesado (ETL)

-> dispatching to node-1: processTelemetry(payload)
   [node-1] processTelemetry(payload)
   ✓ ✓ Procesado (ETL)
```

**Análisis:**
- El cliente NO se cuelga ✓
- Automáticamente intenta el siguiente nodo ✓
- El cliente sigue funcionando ✓
- Node-2 no aparece en futuras llamadas (evicted) ✓

### Output en NODE-1 (gossip):
```
[node-1] Gossip with localhost:6102... OK ✓
[node-1] Gossip with localhost:6101... FAIL (attempt 1/3)
[node-1] Gossip with localhost:6101... FAIL (attempt 2/3)
[node-1] Gossip with localhost:6101... FAIL (attempt 3/3)
[node-1] EVICTED: node-2@localhost:6101 (too many failures)
[node-1] Tombstone set: node-2@localhost:6101 (expires in 30s)
```

---

## 3️⃣ Simulación: Node se Reinicia

### Antes (NODE-2 está muerto):
```
Cluster state:
  node-1: ACTIVE ✓
  node-2: DEAD (tombstone) ✗ [28s remaining]
  node-3: ACTIVE ✓
```

### Reiniciamos NODE-2:
```bash
$ java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102
[node-2] Registered ComputeNode on port 6101
[node-2] Attempting to join...
```

### Output en NODE-1 (gossip inmediato):
```
[node-1] Gossip with localhost:6101...
Gossip response received from node-2@localhost:6101 ✓

Tombstone cleared by direct contact: node-2@localhost:6101
(no waiting 30s!)

Members updated:
  {node-1@localhost:6100, node-2@localhost:6101, node-3@localhost:6102}
```

### Output en NODE-2:
```
Refreshed cluster view via localhost:6100: 
  [node-1@localhost:6100, node-2@localhost:6101, node-3@localhost:6102]

Rejoin complete!
```

---

## 4️⃣ Simulación: Partición de Red

### Inicialmente:
```
CLIENT --- NODE-1 --- NODE-2 --- NODE-3
  (todos conectados)
```

### Cortamos la conexión entre CLIENT y el cluster:
```bash
# En la máquina del cliente: bloquear puerto 6100-6102
sudo iptables -A OUTPUT -p tcp --dport 6100:6102 -j DROP
```

### Output del Cliente:
```
-> dispatching to node-1: processTelemetry(payload)
   node-1@localhost:6100 failed (SocketTimeoutException), trying next...

-> dispatching to node-2: processTelemetry(payload)
   node-2@localhost:6101 failed (SocketTimeoutException), trying next...

-> dispatching to node-3: processTelemetry(payload)
   node-3@localhost:6102 failed (SocketTimeoutException), trying next...

all cached members failed, refreshing view
Attempting refresh from seed: localhost:6100...
   localhost:6100 failed (SocketTimeoutException)
   localhost:6101 failed (SocketTimeoutException)
   localhost:6102 failed (SocketTimeoutException)

❌ Exception: Could not contact any seed or known member
```

**En los Nodos (se siguen comunicando entre ellos):**
```
[node-1] Gossip with localhost:6101... OK ✓
[node-1] Gossip with localhost:6102... OK ✓
(los nodos saben que existen entre ellos)
(pero el cliente está aislado)
```

---

## 5️⃣ Simulación: Cascada de Fallos

### Secuencia catastrófica:
```
t=0s:   CLIENT --- NODE-1 ✓ --- NODE-2 ✓ --- NODE-3 ✓
t=5s:   Cae NODE-1
         CLIENT intenta: NODE-1 ✗ → NODE-2 ✓ (OK)
t=10s:  Cae NODE-2
         CLIENT intenta: NODE-1 ✗ → NODE-2 ✗ → NODE-3 ✓ (OK)
t=15s:  Cae NODE-3
         CLIENT intenta: NODE-1 ✗ → NODE-2 ✗ → NODE-3 ✗
         Cluster MUERTO ❌
```

### Output del Cliente:
```
[t=0s]  -> dispatching to node-1: processTelemetry(payload) ✓
[t=5s]  -> dispatching to node-1: processTelemetry(payload) 
         node-1 failed, trying next...
         -> dispatching to node-2: processTelemetry(payload) ✓

[t=10s] -> dispatching to node-2: processTelemetry(payload)
         node-2 failed, trying next...
         -> dispatching to node-3: processTelemetry(payload) ✓

[t=15s] -> dispatching to node-1: processTelemetry(payload)
         node-1 failed, trying next...
         -> dispatching to node-2: processTelemetry(payload)
         node-2 failed, trying next...
         -> dispatching to node-3: processTelemetry(payload)
         node-3 failed, trying next...
         
❌ ALL MEMBERS FAILED EVEN AFTER A REFRESH
Exception: All members failed even after a refresh
```

---

## 6️⃣ Estructura Interna: Datos del NODE-1

### En memoria (ConcurrentHashMaps):

```
self = Endpoint("node-1", "localhost", 6100)

members = {
  "localhost:6100" → Endpoint("node-1", "localhost", 6100),
  "localhost:6101" → Endpoint("node-2", "localhost", 6101),
  "localhost:6102" → Endpoint("node-3", "localhost", 6102)
}

failures = {
  "localhost:6100" → 0,
  "localhost:6101" → 0,
  "localhost:6102" → 0
}

tombstones = {
  // Vacío si todos están vivos
  // "localhost:6099" → 1684089345000 (timestamp si hay muerto)
}
```

### Después de que NODE-2 cae 3 veces:

```
failures = {
  "localhost:6100" → 0,
  "localhost:6101" → 3,  // ← MAX_FAILURES alcanzado
  "localhost:6102" → 0
}

tombstones = {
  "localhost:6101" → 1684089450000  // expira en 30s
}

members = {
  "localhost:6100" → Endpoint("node-1", "localhost", 6100),
  "localhost:6102" → Endpoint("node-3", "localhost", 6102)
  // NODE-2 fue evicted
}
```

---

## 7️⃣ Traza de Código: Paso a Paso

### Escenario: `client.processTelemetry(payload)` cuando NODE-2 está caído

```java
// CLIENT SIDE
client.processTelemetry(payload)
  ↓
  if (members.isEmpty()) refresh();  // NO vacío
  ↓
  for (attempt = 0; attempt < 2; attempt++) {
    List<Endpoint> snap = members;
    // snap = [node-1, node-3]
    ↓
    int start = cursor.getAndIncrement() % snap.size();
    // start = 1 (round-robin)
    ↓
    for (i = 0; i < 2; i++) {
      Endpoint e = snap.get((1 + i) % 2);
      // i=0: e = snap[1] = node-3
      // i=1: e = snap[0] = node-1
      ↓
      try {
        Registry reg = LocateRegistry.getRegistry("localhost", 6102);
        ComputeService stub = (ComputeService) reg.lookup("ComputeNode");
        return stub.processTelemetry(payload);  // ← ÉXITO!
      } catch (Exception ex) {
        // siguiente...
      }
    }
  }
  return 2.449;  // sqrt(1.5) * 2
```

---

## 8️⃣ Protocolo Gossip: Intercambio Detallado

### Escenario: NODE-1 gossips con NODE-2

```
=== Gossip Round 1 ===

NODE-1 state:
  members: {node-1, node-2, node-3}
  failures: {node-1→0, node-2→0, node-3→0}

NODE-1 → NODE-2:
  RPC call: gossip(
    sender = Endpoint("node-1", "localhost", 6100),
    theirView = {
      Endpoint("node-1", "localhost", 6100),
      Endpoint("node-2", "localhost", 6101),
      Endpoint("node-3", "localhost", 6102)
    }
  )

NODE-2 receives:
  ✓ Direct contact from node-1 → clear any tombstone
  ✓ absorb(node-1) → members["localhost:6100"] = node-1
  ✓ absorb(node-2) → members["localhost:6101"] = node-2
  ✓ absorb(node-3) → members["localhost:6102"] = node-3

NODE-2 returns:
  {
    Endpoint("node-1", "localhost", 6100),
    Endpoint("node-2", "localhost", 6101),
    Endpoint("node-3", "localhost", 6102)
  }

NODE-1 receives response:
  ✓ failures["localhost:6101"] = 0  (reset)
  ✓ members updated
  ✓ Print: "Gossip OK"
```

---

## 9️⃣ Métricas de Rendimiento

### Latency promedio (localhost):
- **RMI lookup**: ~0.5ms
- **processTelemetry() call**: ~1-2ms
- **Gossip round**: ~5-10ms

### Detección de fallos:
- **Timeout RMI**: 3s (default Java RMI)
- **Fallos consecutivos**: 3 = ~9 segundos
- **Tiempo total eviction**: ~10 segundos

### Recuperación:
- **Rejoin (restart)**: Instantáneo (direct contact clears tombstone)
- **Bootstrap**: ~30 segundos en peor caso (si todos los seeds están down)

---

## 🔟 Casos Edge (Bordes)

### Case 1: Cliente arranca ANTES que cualquier nodo
```
CLIENT:     start
            |
NODE-1: ____| (arranca 5s después)

CLIENT.processTelemetry(payload):
  refresh() → Intenta seeds: todos FAIL
  Exception: "Could not contact any seed or known member"
  
  Espera 5 segundos, lo intenta de nuevo:
  refresh() → localhost:6100 OK ✓
  Caches members ✓
  Devuelve resultado ✓
```

### Case 2: Seed list tiene direcciones muertas
```
seeds = {
  "dead-host:6100",      // ← muerto, fue reemplazado
  "localhost:6101",       // ← actual
}

CLIENT.processTelemetry(payload):
  refresh():
    Intenta dead-host:6100... timeout ✗
    Intenta localhost:6101... OK ✓
    Obtiene lista completa actualizada
  Round-robin: OK ✓
```

### Case 3: RMI Registry collision (mismo puerto)
```
java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-1 localhost 6100 ...
java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-2 localhost 6100 ...  // ← MISMO PUERTO!

[node-2] Registered ComputeNode on port 6100
Exception: java.rmi.server.ExportException: 
  Port already in use: 6100
```

### Case 4: Network partición, luego rejoin
```
t=0s:   NODE-1 --- NODE-2
t=5s:   NODE-1   X   NODE-2  (partición)
        node-1 evicts node-2
        node-2 evicts node-1
t=35s:  NODE-1 --- NODE-2    (reconexión)
        
Gossip converge in ~3-6 segundos:
t=36s:  Cluster recuperado
        members = {node-1, node-2}
```

---

## 1️⃣1️⃣ Pseudocódigo: Gossip Loop Principal

```java
// En cada ClusterNode, ejecuta cada 3 segundos:

ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
scheduler.scheduleAtFixedRate(() -> {
    
    List<String> addrs = new ArrayList<>(members.keySet());
    
    for (String addr : addrs) {
        try {
            Endpoint peer = members.get(addr);
            Registry reg = LocateRegistry.getRegistry(
                peer.host, peer.port
            );
            ComputeService stub = 
                (ComputeService) reg.lookup("ComputeNode");
            
            Set<Endpoint> response = stub.gossip(
                self,  // quién soy yo
                new HashSet<>(members.values())  // mi vista
            );
            
            // Éxito: procesar respuesta
            failures.put(addr, 0);  // reset failures
            for (Endpoint e : response) {
                absorb(e);
            }
            
        } catch (Exception e) {
            // Fallo: contar
            int count = failures.getOrDefault(addr, 0) + 1;
            failures.put(addr, count);
            
            if (count >= MAX_FAILURES) {
                evict(addr);  // Fuera del cluster
                tombstones.put(addr, System.currentTimeMillis());
            }
        }
    }
    
    // Si quedó vacío, re-agregar seeds
    if (members.isEmpty()) {
        for (Endpoint seed : seeds) {
            absorb(seed);
        }
    }
    
}, 0, 3, TimeUnit.SECONDS);
```

---

## 1️⃣2️⃣ Debugging: Logs Útiles

### Enable todos los logs:
```bash
java -Djava.util.logging.config.file=logging.properties \
     cluster.Client localhost:6100 localhost:6101 localhost:6102
```

### Monitorear con netstat:
```bash
# Ver todas las conexiones Java RMI
netstat -an | grep 610
# Output:
# ESTABLISHED tcp 127.0.0.1:52345 127.0.0.1:6100
# ESTABLISHED tcp 127.0.0.1:52346 127.0.0.1:6101
# ESTABLISHED tcp 127.0.0.1:52347 127.0.0.1:6102
```

### Debugger breakpoints útiles:
```java
// En ComputeService.gossip()
breakpoint → Log members state aquí
breakpoint → Log failures count aquí

// En Client.processTelemetry()
breakpoint → Log refresh() aquí
breakpoint → Log failover loop aquí
```

---

## Conclusión

Esta simulación muestra:
✅ Bootstrap robusto
✅ Discovery automático
✅ Failover transparente
✅ Recuperación de reinicio
✅ Handling de particiones
❌ (Pero sin resolver split-brain)

El cliente **NUNCA** necesita conocer el estado completo del cluster — solo necesita una semilla y el resto ocurre automáticamente.
