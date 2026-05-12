# 🚀 TLS Quick Start (5 Minutos)

## Para Windows

### Paso 1: Generar Certificados (30 segundos)
```cmd
cd c:\Users\aimar\Downloads\files
generate-certs.bat
```

✅ Crea `server.keystore`

---

### Paso 2: Ejecutar TODO (2 clicks)
```cmd
run-with-tls.bat
```

✅ Se abren 5 ventanas automáticamente:
- NODE-1 (puerto 6100)
- NODE-2 (puerto 6101)
- NODE-3 (puerto 6102)
- CLIENT (ejecutando)
- Control (info)

**Espera 10-15 segundos a que CLIENT termine.**

---

## Para Linux/Mac

### Paso 1: Generar Certificados (30 segundos)
```bash
cd /path/to/files
chmod +x generate-certs.sh run-with-tls.sh
./generate-certs.sh
```

✅ Crea `server.keystore`

---

### Paso 2: Ejecutar TODO (1 comando)
```bash
./run-with-tls.sh
```

✅ Arranca:
- NODE-1, NODE-2, NODE-3 en tmux
- CLIENT ejecutando
- TODO con TLS encriptado

**Espera a que CLIENT termine.**

---

## ✅ Verificación

### Deberías ver en CLIENT:

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

✅ Si ves esto → **TLS FUNCIONANDO CORRECTAMENTE**

---

## 🔐 Verificar Encriptación (Opcional)

Si quieres VER los detalles del TLS handshake:

### Windows:
```cmd
java -Djavax.net.debug=ssl:handshake ^
     -Djavax.net.ssl.keyStore=server.keystore ^
     -Djavax.net.ssl.keyStorePassword=changeit ^
     -cp src cluster.Client localhost:6100
```

### Linux/Mac:
```bash
java -Djavax.net.debug=ssl:handshake \
     -Djavax.net.ssl.trustStore=server.keystore \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -cp src cluster.Client localhost:6100
```

**Verás:**
```
Enabling TLSv1.2
*** ClientHello, TLSv1.2
*** ServerHello, TLSv1.2
*** Certificate, self signed certificate
*** ChangeCipherSpec
*** Finished
```

✓ Esto confirma que TLS está negociándose correctamente

---

## 📊 Comparación: Antes vs Después

| Antes (Sin TLS) | Después (Con TLS) |
|---|---|
| `java cluster.Client localhost:6100` | `java -Djavax.net.ssl.trustStore=... cluster.Client localhost:6100` |
| Tráfico en claro (⚠️ inseguro) | Tráfico encriptado (✅ seguro) |
| OK para desarrollo local | OK para producción |

---

## 🎯 Lo Que Pasó

```
1. Generar certificados
   keytool genera: server.keystore
   
2. NODE arranca
   Lee server.keystore (clave privada + cert)
   Crea SSLServerSocket en puerto 6100
   
3. CLIENT se conecta
   Lee server.keystore (truststore)
   TLS handshake automático
   Comunica de forma encriptada
   
4. Gossip + Compute
   TODO va encriptado:
   - gossip()
   - compute()
   - ping()
```

---

## 🔑 Detalles del Certificado

```
Tipo:          RSA 2048 bits (suficiente)
Algoritmo:     SHA-256
Validez:       365 días
Alias:         rmi-server
Almacenado en: server.keystore
Contraseña:    changeit (cambiar en producción)
```

---

## 🚨 Si Algo Falla

### "keytool: command not found"
```
→ Java no está en PATH
→ Solución: Agrega Java bin a tu PATH
```

### "Address already in use: 6100"
```
→ Otro proceso usa ese puerto
→ Solución: taskkill /F /IM java.exe (Windows)
            pkill -f cluster (Linux/Mac)
```

### "Connection refused"
```
→ Los nodos no arrancaron correctamente
→ Solución: Verifica que aparecen en las ventanas
```

### "SSLException"
```
→ Problema con certificados
→ Solución: Elimina server.keystore y regenera
            generate-certs.bat
```

---

## 🎓 Próximos Pasos

✅ **Funcionando con TLS** → Ve a [ARQUITECTURA.md](ARQUITECTURA.md) para entender cómo funciona

✅ **Quieres debug detallado** → Ve a [RUN_WITH_TLS.md](RUN_WITH_TLS.md)

✅ **Quieres certificados de CA** → Ve a [RUN_WITH_TLS.md](RUN_WITH_TLS.md) → Sección Producción

---

## 💡 Resumen Ultra-Rápido

```
Windows:           Linux/Mac:
generate-certs.bat → ./generate-certs.sh
run-with-tls.bat   → ./run-with-tls.sh
↓                  ↓
✅ HECHO           ✅ HECHO
```

¡Eso es todo! El cluster ahora usa **TLS/SSL** para toda la comunicación. 🔐

---

**Archivos generados:**
- `server.keystore` ← El certificado y clave privada
- `.class files` ← Código compilado

**Documentación:**
- [RESUMEN_TLS.md](RESUMEN_TLS.md) - Resumen completo
- [RUN_WITH_TLS.md](RUN_WITH_TLS.md) - Guía completa
