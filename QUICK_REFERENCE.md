# 🚀 Quick Reference & Guía de Inicio Rápido

## 📋 Tabla Comparativa: Los 4 Componentes

| Archivo | Rol | Puerto | ¿RMI? | ¿Corre solo? | Responsabilidad |
|---------|-----|--------|-------|-------------|-----------------|
| **Endpoint.java** | Datos | - | ✓ (Serializable) | N/A | Identidad de red (IP:puerto) |
| **ComputeService.java** | Interfaz | - | ✓ (Remote) | N/A | Contrato RMI para nodos |
| **ClusterNode.java** | Servidor | 6100+ | ✓ (exports self) | ✓ SÍ | Nodo del cluster (gossip, compute) |
| **Client.java** | Cliente | - | ✗ (consume RMI) | ✓ SÍ | Consumidor de servicios (failover) |

---

## 🎯 ¿Cuál es el Propósito de Cada Clase?

### Endpoint.java
```
Propósito: Ser el "DNI" de un nodo
Ejemplo:  node-1@localhost:6100
Usa:      Serializable (viaja por RMI)
```

### ComputeService.java
```
Propósito: Definir QUYÉ operaciones hacen los nodos
Tipo:     Interface + Remote
Métodos:  getNodeId(), ping(), processTelemetry(), gossip()
Usa:      Implementada por ClusterNode
```

### ClusterNode.java
```
Propósito: SER un nodo del cluster
Tipo:     Servidor RMI
Ejecuta:  
  1. processTelemetry(payload) = ETL score
  2. gossip(sender, view) = descubrimiento
  3. ping() = liveness check

Arranca con: java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode <nodeId> <host> <port> <seed...>
```

### Client.java
```
Propósito: USAR el cluster para hacer tareas
Tipo:     Cliente RMI
Ejecuta:
  1. Bootstrap desde semillas
  2. Cache + round-robin
  3. Failover automático
  4. 12 llamadas processTelemetry()

Arranca con: java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Client <seed:port> [seed:port ...]
```

---

## 🏃 Ejecución en 5 Pasos

### PASO 1: Compilar
```bash
mvn clean install
```

### PASO 2: Arrancar 3 Nodos
```bash
# Terminal 1
java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102

# Terminal 2
java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102

# Terminal 3
java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102
```

### PASO 3: Esperar 3-5 segundos
```bash
# Los nodos se conocen entre sí vía gossip
# Verás en los logs: "Refreshed cluster view..."
```

### PASO 4: Arrancar Cliente
```bash
# Terminal 4
java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Client localhost:6100 localhost:6101 localhost:6102
```

### PASO 5: Ver Resultados
```bash
# Cliente hace 12 processTelemetry() calls:



# ...

```

---

## 🔍 Entender los Logs

### ✅ Startup Normal (NODE-1)
```
[node-1] Registered ComputeNode on port 6100
[node-1] Starting gossip scheduler (interval: 3s)
```
**Significa:** Nodo arrancó, crea su RMI Registry en puerto 6100

### ✅ Bootstrap Esperando Seeds
```
Gossip with localhost:6101... failed (ConnectException)
Gossip with localhost:6102... failed (ConnectException)
```
**Significa:** Node-1 es el primero en arrancar, no encuentra otros. Normal.

### ✅ Otro Nodo Se Une
```
Refreshed cluster view via localhost:6100: 
  [node-1@localhost:6100, node-2@localhost:6101]
```
**Significa:** Node-2 se conectó a Node-1 y obtuvo la lista de miembros

### ✅ Cliente Bootstrap
```
Refreshed cluster view via localhost:6100: 
  [node-1@localhost:6100, node-2@localhost:6101, node-3@localhost:6102]
```
**Significa:** Cliente se conectó y cacheó todos los miembros

### ✅ Cliente Balanceo
```
-> dispatching to node: processTelemetry(payload)
-> dispatching to node: processTelemetry(payload)
-> dispatching to node: processTelemetry(payload)
-> dispatching to node: processTelemetry(payload)
```
**Significa:** Cliente alterna entre nodos (carga balanceada)

### ❌ Fallo (si un nodo cae)
```
node-2@localhost:6101 failed (ConnectException), trying next...
```
**Significa:** Cliente intentó NODE-2, falló, probará NODE-3

### ❌ Eviction (después de 3 fallos)
```
EVICTED: node-2@localhost:6101 (too many failures)
Tombstone set: node-2@localhost:6101 (expires in 30s)
```
**Significa:** NODE-1 dio por perdido a NODE-2, lo marcó muerto por 30s

### ✅ Rejoin (nodo reinicia)
```
Tombstone cleared by direct contact: node-2@localhost:6101
```
**Significa:** NODE-2 contactó a NODE-1, se borraron los 30 segundos de espera

---

## 🐛 Troubleshooting

### Problema: "Port already in use"
```
Exception: java.rmi.server.ExportException: Port already in use: 6100

Solución:
1. Cambia el puerto en el comando:
   java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-new localhost 6103 ...
2. O mata el proceso anterior:
   pkill -f "cluster.ClusterNode"
3. O espera a que Java libere el puerto (~1 minuto)
```

### Problema: Cliente dice "Could not contact any seed"
```
Exception: Could not contact any seed or known member

Causas posibles:
❌ Ningún nodo está corriendo
❌ Tipeo mal la dirección (node-1 vs localhost)
❌ Firewall bloqueando puertos 6100-6102
❌ Nodos en máquinas distintas y no hay conectividad

Solución:
✓ Asegúrate que mínimo 1 nodo está running
✓ Verifica: netstat -an | grep 610
✓ Test: telnet localhost 6100
```

### Problema: Cliente se cuelga
```
-> dispatching to node: processTelemetry(payload)
(sin output por 60+ segundos)

Causa: Timeout de RMI (default ~30s)

Solución:
java -Dsun.rmi.transport.tcp.responseTimeout=5000 \
     cluster.Client localhost:6100
```

### Problema: Logs no aparecen
```
Solución: Asegúrate que usas System.out.println()
(Los System.err.println() van a stderr)

O redirige:
java -cp client/target/client-1.0-SNAPSHOT.jar cluster.Client localhost:6100 2>&1 | tee output.log
```

---

## 📊 Resumen Visual: ¿Quién habla con quién?

```
┌──────────────┐
│   CLIENT     │
└──────┬───────┘
       │
       │ 1. gossip() ← Descubrimiento
       │
       ▼
   ┌───────────┐
   │ Seed Nodo │ (cualquiera: NODE-1, 2, o 3)
   └─────┬─────┘
         │
         │ Devuelve lista completa
         │
   ┌─────▼─────────────────────────┐
   │ CLIENT cacheado:              │
   │  {NODE-1, NODE-2, NODE-3}     │
   └─────┬─────────────────────────┘
         │
         │ 2. processTelemetry() ← Tarea
         │
    ┌────┴────┬──────────┬──────────┐
    ▼         ▼          ▼          ▼
  NODE-1    NODE-2    NODE-3     NODE-1 (round-robin)
  sqrt()    sqrt()    sqrt()      sqrt()
    │         │         │          │
    └─────────┴─────────┴──────────┘
            Respuestas
```

---

## 🎓 Vocabulario Clave

| Término | Significado | Ejemplo |
|---------|-------------|---------|
| **Bootstrap** | Arranque inicial (cliente obtiene lista de miembros) | Client llama gossip() a una semilla |
| **Gossip** | Protocolo de chismes (nodos intercambian membresía) | NODE-1 le dice a NODE-2: "conozco a NODE-3" |
| **Failover** | Cambio automático a alternativa | Cliente intenta NODE-2, falla, intenta NODE-3 |
| **Tombstone** | Marca de "nodo muerto" (temporal) | NODE-1 evicta NODE-2, lo marca muerto por 30s |
| **Anti-entropy** | Protocolo que reduce el desorden | Gossip hace que todos aprendan lo mismo |
| **Round-robin** | Alternancia circular | Llamada 1→NODE-1, 2→NODE-2, 3→NODE-3, 4→NODE-1 |
| **SPOF** | Single Point of Failure (punto único de fallo) | No hay SPOF aquí (sin servidor central) |
| **RMI** | Remote Method Invocation (llamadas remotas Java) | `stub.processTelemetry(payload)` es una llamada remota |
| **Registry** | Directorio de servicios RMI | Cada nodo crea su propio registry |

---

## 🔗 Relaciones entre Clases

```
ComputeService (interfaz)
       ▲
       │ implements
       │
ClusterNode ◄─── extends UnicastRemoteObject
   │  │  │
   │  │  └─► Mantiene: members (Map<String, Endpoint>)
   │  │
   │  └──► Usa: Endpoint (serializable)
   │
   └──► Expone sobre RMI
           (client puede llamar)

Client
   │
   ├─► Conoce: List<Endpoint> seeds
   │
   ├─► Cacheado: List<Endpoint> members
   │
   └─► Llamada RMI
       └─► Stub (ComputeService)
           └─► ClusterNode remoto
```

---

## 📈 Escalabilidad

### ¿Qué pasa si agregamos 100 nodos?

| Aspecto | Impacto |
|---------|---------|
| Gossip load | Cada nodo itera 100 peers cada 3s (~33/s) → Escalable |
| Cliente discovery | Mismo (bootstrap desde 1 seed) |
| Memory (per node) | members = Map 100 * Endpoint → ~10KB → OK |
| Network (gossip) | 100 nodos * 100 gossips/3s ≈ 3300 RMI calls/s → Posible saturar red |
| Failover latency | Sin cambios (round-robin sigue igual) |

**Conclusión:** Escalable hasta ~50-100 nodos con gossip cada 3s. Para más, necesitas gossip probabílístico (no todos con todos).

---

## 🎮 Ejercicios Propuestos

### Ejercicio 1: Simular fallo
```bash
# Terminal 4 (Node-3):
# Ctrl+C mientras cliente corre
# Observa cómo cliente sigue funcionando sin NODE-3
```

### Ejercicio 2: Agregar métodos a ComputeService
```java
// Agrega a ComputeService.java:
String getStatus() throws RemoteException;

// Implementa en ClusterNode.java:
@Override
public String getStatus() {
    return "Members: " + members.keySet();
}
```

### Ejercicio 3: Monitorear estado en vivo
```java
// En Client.java, antes de processTelemetry():
while (true) {
    System.out.println("Current members: " + 
        client.members.values());
    Thread.sleep(1000);
    client.processTelemetry(payload);
}
```

### Ejercicio 4: Agregar logging a gossip
```java
// En ClusterNode.gossip():
System.out.println("GOSSIP: sender=" + sender + 
    ", received=" + theirView.size() + " members");
```

---

## 🚦 Estado del Proyecto

```
Funcionalidad       Estado      Notas
──────────────────────────────────────────────────────
Bootstrap           ✅ DONE     Desde semillas
Gossip              ✅ DONE     Cada 3 segundos
Failover            ✅ DONE     Automático round-robin
Tombstones          ✅ DONE     30 segundos TTL
Rejoin              ✅ DONE     Immediatamente si contacto directo
Persistencia        ❌ TODO     (no hay base de datos)
Encriptación        ✅ Implementada (RMI con TLS)
Split-brain solve   ❌ TODO     (no resuelto)
Rebalanceo          ❌ TODO     (no redistribuye en altas cargas)
```

---

## 🏁 Conclusión Rápida

| Pregunta | Respuesta |
|----------|-----------|
| ¿Qué es? | Cluster P2P con gossip, sin servidor central |
| ¿Cómo arranca? | Cliente contacta CUALQUIER seed, obtiene lista completa |
| ¿Qué hace? | Calcula sqrt(abs(x))*2 en cualquier nodo disponible |
| ¿Si falla un nodo? | Cliente automáticamente intenta otro (transparente) |
| ¿Si vuelve a levantarse? | Rejoin inmediato (gossip directo borra tombstone) |
| ¿Punto único de fallo? | NO (todo es redundante) |
| ¿Producción-ready? | No (falta: persistencia, encriptación, monitoreo) |

---

---

## 🔐 TLS/SSL Support

El cluster **soporta encriptación TLS** para comunicación segura.

### Quick Start TLS:

```bash
# Windows
generate-certs.bat
run-with-tls.bat

# Linux/Mac
chmod +x generate-certs.sh run-with-tls.sh
./run-with-tls.sh
```

### Ejecutar Manualmente Con TLS:

```bash
# Nodo
java -Djavax.net.ssl.keyStore=server.keystore \
     -Djavax.net.ssl.keyStorePassword=changeit \
     -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101

# Cliente
java -Djavax.net.ssl.trustStore=server.keystore \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -cp client/target/client-1.0-SNAPSHOT.jar cluster.Client localhost:6100
```

| Documento | Propósito |
|-----------|-----------|
| [TLS_QUICK_START.md](TLS_QUICK_START.md) | Inicio rápido TLS (5 min) |
| [RESUMEN_TLS.md](RESUMEN_TLS.md) | Resumen completo de TLS |
| [RUN_WITH_TLS.md](RUN_WITH_TLS.md) | Guía detallada y troubleshooting |

---

**📞 Para más detalles, ver:** 
- `ARQUITECTURA.md` - Explicación completa
- `SIMULACIONES_Y_EJEMPLOS.md` - Casos de uso
- `TLS_QUICK_START.md` - Inicio rápido con TLS
