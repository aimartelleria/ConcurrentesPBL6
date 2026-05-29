# 🖥️ Guía de Inicio del Servidor Clúster (ClusterNode)

Esta guía explica detalladamente cómo compilar, configurar e iniciar los nodos del clúster de procesamiento distribuido (`ClusterNode`) en el proyecto, tanto en modo estándar (sin TLS) como en modo seguro (con TLS/SSL).

---

## 📋 Requisitos Previos

Antes de arrancar los servidores, asegúrate de tener instalado y configurado lo siguiente en tu sistema:
1. **Java JDK 21 o superior**: El clúster utiliza **Virtual Threads** (hilos virtuales) introducidos de forma estable en Java 21 para lograr una concurrencia masiva y no bloqueante.
2. **Apache Maven**: Herramienta de compilación y gestión de dependencias para proyectos Java.
3. **Kafka**: Debe estar ejecutándose localmente (por ejemplo, en un contenedor Docker en el puerto `9092` / `9094`) para que la inyección de telemetría funcione correctamente a través del cliente.
   ```bash
   docker compose up -d kafka
   ```

---

## 🛠️ Paso 1: Compilación del Proyecto

Antes de ejecutar los nodos, es necesario compilar todos los submódulos (`common`, `server`, `client`) con Maven para generar los archivos `.jar` empaquetados.

Ejecuta el siguiente comando desde la raíz del workspace (`c:\Users\aimar\Downloads\files`):

```bash
mvn clean package -DskipTests
```

**Salida esperada:**
Esto compilará los módulos y colocará los ejecutables en sus respectivas carpetas:
- El servidor compilado se ubicará en: `server/target/server-1.0-SNAPSHOT.jar`
- El cliente compilado se ubicará en: `client/target/client-1.0-SNAPSHOT.jar`

---

## 🏃 Paso 2: Ejecución de Nodos (Sin TLS)

En el modo estándar sin cifrado, los nodos se comunican en texto claro a través de la red RMI.

### Sintaxis del Comando:
```bash
java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode <nodeId> <host> <port> [seedHost:Port ...]
```

### Parámetros Explicados:
1. `<nodeId>`: Nombre único que identifica al nodo en la red (ej. `node-1`).
2. `<host>`: Dirección IP o hostname del servidor donde se ejecuta el nodo (usualmente `localhost` para pruebas locales).
3. `<port>`: Puerto en el que este nodo levantará su propio RMI Registry local (ej. `6100`).
4. `[seedHost:Port ...]`: Lista de direcciones "semilla" (nodos de bootstrap). El nodo intentará conectarse a estas direcciones para descubrir a los demás miembros del clúster a través del protocolo *Gossip*. Al menos una semilla debe ser accesible en el arranque.

### Ejemplo de Arranque de un Clúster de 3 Nodos:

Abre tres terminales independientes y ejecuta en cada una de ellas lo siguiente:

* **Terminal 1 (Nodo 1 en puerto 6100):**
  ```bash
  java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102
  ```

* **Terminal 2 (Nodo 2 en puerto 6101):**
  ```bash
  java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102
  ```

* **Terminal 3 (Nodo 3 en puerto 6102):**
  ```bash
  java -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102
  ```

---

## 🔐 Paso 3: Ejecución de Nodos (Con TLS/SSL)

Para entornos de producción o redes inseguras, se recomienda cifrar todo el tráfico RMI y el intercambio Gossip mediante certificados TLS/SSL.

### 🔑 A. Generar Certificados (Keystore)
Primero, debes generar el archivo de certificados `server.keystore`. Ejecuta el script automatizado provisto en la raíz del proyecto:

* **En Windows (CMD / PowerShell):**
  ```cmd
  generate-certs.bat
  ```
* **En Linux / Mac:**
  ```bash
  chmod +x generate-certs.sh
  ./generate-certs.sh
  ```

Esto creará un keystore auto-firmado llamado `server.keystore` con la contraseña predeterminada `changeit`.

### 🚀 B. Iniciar los Nodos en Modo TLS
Para activar TLS en la JVM, se deben inyectar dos propiedades del sistema Java mediante la bandera `-D` antes del comando de ejecución:
- `javax.net.ssl.keyStore`: Ruta al almacén de claves (keystore).
- `javax.net.ssl.keyStorePassword`: Contraseña del keystore (ej. `changeit`).

Abre tres terminales independientes y ejecuta lo siguiente:

* **Terminal 1 (Nodo 1 con TLS):**
  ```bash
  java -Djavax.net.ssl.keyStore=server.keystore -Djavax.net.ssl.keyStorePassword=changeit -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102
  ```

* **Terminal 2 (Nodo 2 con TLS):**
  ```bash
  java -Djavax.net.ssl.keyStore=server.keystore -Djavax.net.ssl.keyStorePassword=changeit -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102
  ```

* **Terminal 3 (Nodo 3 con TLS):**
  ```bash
  java -Djavax.net.ssl.keyStore=server.keystore -Djavax.net.ssl.keyStorePassword=changeit -cp server/target/server-1.0-SNAPSHOT.jar cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102
  ```

> [!IMPORTANT]
> **El Cliente RMI también debe usar TLS:**
> Si los nodos del servidor se arrancan con TLS, el cliente RMI (`Client`) fallará al conectarse a menos que se ejecute también configurando el truststore:
> ```bash
> java -Djavax.net.ssl.trustStore=server.keystore -Djavax.net.ssl.trustStorePassword=changeit -cp client/target/client-1.0-SNAPSHOT.jar cluster.Client localhost:6100 localhost:6101 localhost:6102
> ```

---

## 🔍 Parámetros Adicionales de la JVM

Al desplegar el servidor en red o depurar conexiones, te resultarán de gran utilidad los siguientes parámetros de ejecución de la JVM:

| Propiedad | Ejemplo / Valor | Utilidad / Propósito |
|---|---|---|
| `-Djava.rmi.server.hostname` | `-Djava.rmi.server.hostname=192.168.1.50` | Fuerza a RMI a exponer esta dirección IP en el Stub en lugar de `localhost`. Imprescindible para comunicaciones entre múltiples máquinas reales. |
| `-Djavax.net.debug` | `-Djavax.net.debug=ssl:handshake` | Muestra en consola la traza de depuración completa del handshake TLS/SSL en cada conexión RMI. Útil si las llamadas TLS fallan. |

---

## ⚠️ Solución de Problemas del Servidor

### 1. Error: `java.rmi.server.ExportException: Port already in use: 6100`
* **Causa**: Ya hay una instancia de un nodo (u otro proceso) corriendo en ese puerto.
* **Solución**: Cierra los procesos Java huérfanos que hayan quedado activos en segundo plano.
  * **En Windows (PowerShell)**: `Stop-Process -Name java -Force` o `taskkill /F /IM java.exe` en CMD.
  * **En Linux/Mac**: `pkill -f "ClusterNode"` o `killall java`.

### 2. Los nodos no se descubren entre sí (Vista de miembros vacía)
* **Causa**: El puerto Gossip no es accesible, o la primera semilla indicada no está corriendo todavía.
* **Solución**: 
  - Asegúrate de arrancar primero el nodo que actúa como la semilla principal.
  - Verifica que los firewalls locales no bloqueen los puertos `6100`, `6101` y `6102`.
  - Si estás en Docker, asegúrate de que todos los contenedores pertenezcan a la misma red puente.

### 3. Error `javax.net.ssl.SSLException` o `SSLHandshakeException`
* **Causa**: El cliente y el servidor no coinciden en la configuración SSL, falta el keystore, o el password suministrado es incorrecto.
* **Solución**: Regenera el keystore con `generate-certs.bat` y asegúrate de que tanto el comando del nodo como el del cliente apunten a la ruta exacta y usen el password correcto (`changeit`).
