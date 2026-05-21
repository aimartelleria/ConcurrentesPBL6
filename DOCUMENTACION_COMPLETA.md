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
│   Agente Go    ├─────────────►│    RabbitMQ      │ (Cola de Ingesta)
│  (Data Source) │              │    (Broker)      │
└────────────────┘              └────────┬─────────┘
                                         │ Consumo (AMQP)
                                         ▼
                 ┌────────────────────────────────────────────────────────┐
                 │                      CLIENTE JAVA                      │
                 │  (Consumidor RabbitMQ + RMI Bootstrap + Load Balancer) │
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
2. **Buffer (RabbitMQ):** Desacopla la emisión de datos de la capacidad y velocidad de procesamiento del cluster de Java.
3. **Edge / Entrypoint (Cliente Java):** Actúa como un puente. Escucha mensajes de RabbitMQ y los balancea (Round-Robin tolerante a fallos) enviándolos por RMI al cluster.
4. **Compute Grid (Servidores ClusterNode):** Malla de nodos que ejecutan el motor de cálculo genérico (`Task`) o procesan telemetría localmente. Mantienen su estado (quién está vivo/muerto) "cotilleando" (Gossip) entre sí.

---

## 3. 🧠 Justificación Tecnológica (Stack)

Cada tecnología en este ecosistema no fue elegida al azar, sino que resuelve un problema específico del procesamiento o la arquitectura en red.

### 🌟 3.1. Java 21 y Virtual Threads (Project Loom)
En la parte del procesamiento RMI (Motor de Cálculo) y el puente (Cliente), la concurrencia tradicional generaría "cuellos de botella" graves: RMI bloquea los hilos durante la conexión y espera.
- **Justificación:** Los *Virtual Threads* permiten instanciar millones de hilos superligeros que se "aparcan" gratis cuando esperan I/O (la red), evitando que el motor se sature.
- **Resultado:** En nuestra implementación, RabbitMQ o las peticiones RMI usan `Executors.newVirtualThreadPerTaskExecutor()`, logrando alto rendimiento sin un límite duro de *ThreadPools*.

### 🌟 3.2. Java RMI (Remote Method Invocation)
Sirve como vía de comunicación RPC (Remote Procedure Call) a nivel interno.
- **Justificación:** Otorga la capacidad de invocar métodos remotos (como `processTelemetry` o `executeTask(Task)`) en el clúster con serialización nativa de objetos complejos Java (como clases `Task`). El *overhead* de red es compensado al no requerir parseos de JSON/HTTP continuamente.
- **Resultado:** Las lógicas abstractas se pueden distribuir de forma nativa enviando *Bytecode*.

### 🌟 3.3. Protocolo Gossip P2P
Para tener un cluster *"master-less"* (sin un líder ni servidor centralizado).
- **Justificación:** Un servidor central o servicio estilo ZooKeeper añade complejidad y un punto único de fallo. *Gossip* emula el comportamiento de un virus o "chisme": cada nodo le cuenta a otro a quién conoce, propagando la información de salud y topología gradualmente por la red.
- **Resultado:** Alta tolerancia a la partición y fallos en hardware comercial estándar. O(N²) de coste pasivo pero inmensa robustez.

### 🌟 3.4. RabbitMQ
El software bróker AMQP.
- **Justificación:** Si los agentes de Go enviasen los datos en vivo directo a los nodos, la caída de estos haría perder métricas, y un aluvión de telemetría haría colapsar la capa de red con denegación de servicio (Backpressure problems). RabbitMQ introduce **desacoplamiento** y actúa de "amortiguador" (*buffer*).
- **Resultado:** Si el cluster RMI de Java se apaga o sufre, RabbitMQ retiene ordenadamente los mensajes hasta que vuelva.

### 🌟 3.5. Golang (Agentes de Extracción)
El `AgenteGo` se encarga de extraer la métrica e iniciar el ciclo vital de los datos.
- **Justificación:** Go se compila como un binario estático, sin requerir una JVM de 200MB en las máquinas de donde obtiene los datos. Es altamente concurrente y consume ~15MB-30MB de memoria en ejecución. Ideal para *sidecars* y sistemas embebidos de monitorización.

### 🌟 3.6. Apache Avro
Para la codificación del mensaje de RabbitMQ a RMI.
- **Justificación:** Frente a JSON que necesita descifrar strings, Avro comprime los datos de forma binaria apoyado en un *schema* fijo, reduciendo drásticamente (hasta en un 70%) el tamaño de banda ocupada en red y acelerando la de-serialización.

### 🌟 3.7. TLS/SSL para Cifrado
Para la comunicación en el interior del cluster.
- **Justificación:** RMI en crudo carece de seguridad y transita en texto o bytes predecibles por la red (vulnerable a *man-in-the-middle* o ejecución de comandos remota maliciosa). Inyectando `-Djavax.net.ssl.trustStore` y fábricas de SSL personalizadas, el cluster RMI pasa a ser encriptado, pudiendo interconectar zonas geográficas diferentes con seguridad.

---

## 4. 🔀 Flujo Completo y Ciclo de Vida de los Datos

Para empaquetar cómo coopera todo, este es el viaje de 1 milisegundo de tu aplicación:

1. **[GO]** Un sensor en el Agente de Go detecta que la CPU local está al 90%. Empaqueta esta información.
2. **[GO -> AVRO]** Go serializa la métrica utilizando codificación binaria *Avro* y un schema compartido.
3. **[GO -> AMQP]** Go envía este paquete binario al Topic configurado previamente en *RabbitMQ*.
4. **[MQ]** RabbitMQ encola el paquete y se asegura de que exista persistencia.
5. **[JAVA CLIENT]** El nodo `Client` ("el consumidor"), escuchando mediante un hilo Virtual, recibe instantáneamente desde la cola MQ el paquete Avro.
6. **[JAVA CLIENT -> LOAD BALANCER]** El `Client` verifica su caché Gossip. Descubre que hay 3 nodos vivos. El puntero *round-robin* selecciona el `NODO 2`.
7. **[JAVA RMI (TLS)]** `Client` invoca `stubNODO2.processTelemetry(paquete_avro)`. El flujo viaja por red con encriptación TLS.
8. **[JAVA NODE]** El `NODO 2` (Motor de Cálculo) recibe el Byte Array instanciando un *Virtual Thread*, deserializa el paquete y efectúa la lógica de transformación / cálculo o almacenamiento final.


## 5. 🚀 Resiliencia (Casos Extremos)

- **Un Nodo de procesado se quema/cae:** Los demás dejarán de recibir pings. Tras 3-5 iteraciones, el nodo se elimina del Directorio Global P2P. El Cliente al fallar su intento inicial, lanza un *Failover* automático e intenta el siguiente nodo transparente. Ningún dato de RabbitMQ se pierde.
- **Go genera 100 veces más tráfico del habitual:** RabbitMQ se encargará del encolamiento. El cluster procesará tan rápido como pueda.
- **Se incorporan más Nodos:** Simplemente arrancan un nuevo proceso instanciado apontando a la capa de Semillas (*Seeds*). En un par de *pings* el cluster entero conoce los nuevos recursos computacionales disponibles de forma orgánica.