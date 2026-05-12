# 🔐 Ejecutar Cluster RMI con TLS/SSL

## 📋 Paso a Paso

### Paso 1: Generar Certificados

#### En Windows:
```bash
cd c:\Users\aimar\Downloads\files
generate-certs.bat
```

#### En Linux/Mac:
```bash
cd /path/to/files
chmod +x generate-certs.sh
./generate-certs.sh
```

**Salida esperada:**
```
Generating self-signed certificate for RMI TLS...

✓ Certificate generated successfully!

Files created:
  - server.keystore (contains private key and certificate)
```

**Archivo generado:**
- `server.keystore` (contiene la clave privada y certificado auto-firmado)

---

### Paso 2: Compilar (si no lo has hecho)

```bash
cd c:\Users\aimar\Downloads\files
javac src/cluster/*.java
```

---

### Paso 3: Ejecutar Nodos CON TLS

#### Terminal 1: NODE-1
```bash
cd c:\Users\aimar\Downloads\files

java -Djavax.net.ssl.keyStore=server.keystore ^
     -Djavax.net.ssl.keyStorePassword=changeit ^
     -cp src cluster.ClusterNode node-1 localhost 6100 ^
     localhost:6100 localhost:6101 localhost:6102
```

#### Terminal 2: NODE-2
```bash
cd c:\Users\aimar\Downloads\files

java -Djavax.net.ssl.keyStore=server.keystore ^
     -Djavax.net.ssl.keyStorePassword=changeit ^
     -cp src cluster.ClusterNode node-2 localhost 6101 ^
     localhost:6100 localhost:6101 localhost:6102
```

#### Terminal 3: NODE-3
```bash
cd c:\Users\aimar\Downloads\files

java -Djavax.net.ssl.keyStore=server.keystore ^
     -Djavax.net.ssl.keyStorePassword=changeit ^
     -cp src cluster.ClusterNode node-3 localhost 6102 ^
     localhost:6100 localhost:6101 localhost:6102
```

**Output esperado:**
```
Started node node-1@localhost:6100 (own registry on port 6100)
[node-1] view: [node-1@localhost:6100]
[node-1] view: [node-1@localhost:6100, node-2@localhost:6101]
[node-1] view: [node-1@localhost:6100, node-2@localhost:6101, node-3@localhost:6102]
```

---

### Paso 4: Ejecutar Cliente CON TLS

#### Terminal 4: CLIENT
```bash
cd c:\Users\aimar\Downloads\files

java -Djavax.net.ssl.trustStore=server.keystore ^
     -Djavax.net.ssl.trustStorePassword=changeit ^
     -cp src cluster.Client localhost:6100 localhost:6101 localhost:6102
```

**Output esperado:**
```
Refreshed cluster view via localhost:6100: 
  [node-1@localhost:6100, node-2@localhost:6101, node-3@localhost:6102]

-> dispatching to node-1
   [node-1] compute(0.000)
   result = 0.000

-> dispatching to node-2
   [node-2] compute(1.500)
   result = 2.449

...
```

---

## 🔑 Propiedades JVM Explicadas

### Para Nodos (Servidores):

| Propiedad | Valor | Significado |
|-----------|-------|-------------|
| `-Djavax.net.ssl.keyStore` | `server.keystore` | Archivo con la clave privada del servidor |
| `-Djavax.net.ssl.keyStorePassword` | `changeit` | Contraseña del keystore |
| `-Djavax.net.ssl.keyStoreType` | `JKS` | (opcional) Formato del keystore |

### Para Cliente:

| Propiedad | Valor | Significado |
|-----------|-------|-------------|
| `-Djavax.net.ssl.trustStore` | `server.keystore` | Archivo de certificados confiables |
| `-Djavax.net.ssl.trustStorePassword` | `changeit` | Contraseña del truststore |

**Nota:** Usamos el mismo archivo `server.keystore` para ambos roles porque:
- Es un certificado auto-firmado (development)
- El cliente necesita confiar en él
- En producción, usarías archivos separados

---

## 🖥️ Scripts Ready-to-Use

### Script para ejecutar TODO (Windows)

**Crear `run-with-tls.bat`:**
```batch
@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

if not exist "server.keystore" (
    echo Generando certificados...
    call generate-certs.bat
)

if not exist "server.keystore" (
    echo ERROR: No se pudo generar server.keystore
    exit /b 1
)

echo.
echo Compilando...
javac src\cluster\*.java 2>nul

echo.
echo Iniciando NODE-1 en Terminal nueva...
start cmd /k "java -Djavax.net.ssl.keyStore=server.keystore -Djavax.net.ssl.keyStorePassword=changeit -cp src cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102"

timeout /t 2

echo.
echo Iniciando NODE-2 en Terminal nueva...
start cmd /k "java -Djavax.net.ssl.keyStore=server.keystore -Djavax.net.ssl.keyStorePassword=changeit -cp src cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102"

timeout /t 2

echo.
echo Iniciando NODE-3 en Terminal nueva...
start cmd /k "java -Djavax.net.ssl.keyStore=server.keystore -Djavax.net.ssl.keyStorePassword=changeit -cp src cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102"

timeout /t 3

echo.
echo Iniciando CLIENT en Terminal nueva...
start cmd /k "java -Djavax.net.ssl.trustStore=server.keystore -Djavax.net.ssl.trustStorePassword=changeit -cp src cluster.Client localhost:6100 localhost:6101 localhost:6102"

echo.
echo ✓ Todos los procesos iniciados!
```

**Usar:**
```bash
run-with-tls.bat
```

---

## 🔍 Verificar Conexión TLS

### Ver detalles del handshake TLS

Ejecuta un nodo con debug:
```bash
java -Djavax.net.debug=ssl:handshake ^
     -Djavax.net.ssl.keyStore=server.keystore ^
     -Djavax.net.ssl.keyStorePassword=changeit ^
     -cp src cluster.ClusterNode node-1 localhost 6100 localhost:6100
```

**Output (parcial):**
```
Enabling TLSv1.2
Enabling TLSv1.3
...
*** ClientHello, TLSv1.2
*** ServerHello, TLSv1.2
*** Certificate, self signed certificate
*** ServerHelloDone
*** ClientKeyExchange, RSA PreMasterSecret, TLSv1.2
*** ChangeCipherSpec
*** Finished
```

---

## ⚠️ Solución de Problemas

### Error: "javax.net.ssl.SSLException: No appropriate protocol"

**Causa:** Java < 1.8 o TLS deshabilitado

**Solución:**
```bash
# Verifica versión Java
java -version

# Debes tener Java 8+
```

---

### Error: "trustAnchors parameter must be non-empty"

**Causa:** Truststore no existe o contraseña incorrecta

**Solución:**
```bash
# Regenera certificados
generate-certs.bat

# Verifica que server.keystore existe
ls server.keystore

# Asegúrate de usar password correcta (changeit)
```

---

### Error: "keytool command not found"

**Causa:** Java JDK no está en PATH

**Solución (Windows):**
```bash
# Encuentra donde está Java
where java

# Ejemplo: C:\Program Files\Java\jdk1.8.0_271\bin\java
# Entonces keytool está en:
# C:\Program Files\Java\jdk1.8.0_271\bin\keytool

# Opción 1: Agrega al PATH
setx PATH "%PATH%;C:\Program Files\Java\jdk1.8.0_271\bin"

# Opción 2: Usa ruta completa
"C:\Program Files\Java\jdk1.8.0_271\bin\keytool" -genkey ...
```

---

### Error: "Address already in use"

**Causa:** Puerto 6100, 6101 o 6102 ya en uso

**Solución:**
```bash
# Mata procesos Java anteriores
taskkill /F /IM java.exe

# O usa puertos diferentes
java ... cluster.ClusterNode node-4 localhost 6103 localhost:6100 localhost:6101 localhost:6102
```

---

## 🔐 Seguridad

### ⚠️ Certificados Auto-Firmados (Development Only)

El certificado que generas es **auto-firmado**:
- ✓ Bueno para desarrollo/testing
- ❌ Malo para producción (no verificado por CA)
- ❌ Navegadores/clientes mostrarán warnings

### Para Producción:

1. **Obtén un certificado de una CA** (ej: Let's Encrypt, DigiCert)
2. **Importa a keystore:**
   ```bash
   keytool -import -alias rmi-prod -file cert.crt -keystore prod.keystore
   ```
3. **Usa en lugar de server.keystore**

---

## 📊 Comparación: Con TLS vs Sin TLS

| Aspecto | Sin TLS | Con TLS |
|---------|---------|---------|
| **Comando** | `java cluster.Client localhost:6100` | `java -Djavax.net.ssl.trustStore=... cluster.Client localhost:6100` |
| **Seguridad** | 🔴 Tráfico en claro | 🟢 Encriptado |
| **Performance** | 🟢 Rápido | 🟡 ~5-10% más lento |
| **Setup** | 🟢 Trivial | 🟡 Requiere certificados |
| **Producción** | ❌ NO | ✅ SÍ |

---

## 🎯 Resumen Rápido

```bash
# 1. Generar certificados (una sola vez)
generate-certs.bat

# 2. Ejecutar NODE-1 CON TLS
java -Djavax.net.ssl.keyStore=server.keystore ^
     -Djavax.net.ssl.keyStorePassword=changeit ^
     -cp src cluster.ClusterNode node-1 localhost 6100 localhost:6100 localhost:6101 localhost:6102

# 3. Ejecutar NODE-2 CON TLS
java -Djavax.net.ssl.keyStore=server.keystore ^
     -Djavax.net.ssl.keyStorePassword=changeit ^
     -cp src cluster.ClusterNode node-2 localhost 6101 localhost:6100 localhost:6101 localhost:6102

# 4. Ejecutar NODE-3 CON TLS
java -Djavax.net.ssl.keyStore=server.keystore ^
     -Djavax.net.ssl.keyStorePassword=changeit ^
     -cp src cluster.ClusterNode node-3 localhost 6102 localhost:6100 localhost:6101 localhost:6102

# 5. Ejecutar CLIENT CON TLS
java -Djavax.net.ssl.trustStore=server.keystore ^
     -Djavax.net.ssl.trustStorePassword=changeit ^
     -cp src cluster.Client localhost:6100 localhost:6101 localhost:6102
```

---

## 📚 Referencias

- [Java SSL/TLS Documentation](https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html)
- [RMI over SSL/TLS](https://docs.oracle.com/javase/8/docs/technotes/guides/rmi/)
- [Keytool Manual](https://docs.oracle.com/javase/8/docs/technotes/tools/unix/keytool.html)
