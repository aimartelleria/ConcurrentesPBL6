# Script de métricas para Kafka

Este proyecto contiene un script en Go (`SubscriberKafka.go` / `ConsumerKafka.go` / `metrics_shared.go`) que recolecta métricas del sistema y las publica como mensajes codificados en Avro en un topic de Kafka.

## Qué hace

El script realiza estos pasos:

1. Obtiene métricas del equipo.
2. Calcula el uso de CPU, RAM y disco.
3. Intenta leer la temperatura del sistema con varias fuentes.
4. Serializa todo usando Apache Avro de manera eficiente.
5. Publica el resultado en Kafka en el topic `telemetry`.

## Requisitos

- Go instalado.
- Un broker Kafka accesible.
- Acceso de red al broker.

El archivo `go.mod` indica estas dependencias principales:

- `github.com/segmentio/kafka-go`
- `github.com/shirou/gopsutil/v3`
- `github.com/hamba/avro/v2`

## Estructura del mensaje

El mensaje enviado a Kafka está codificado en formato Avro binario. Al deserializarlo, tiene la siguiente estructura conceptual (JSON):

```json
{
  "timestamp": 1710000000,
  "cpu_percent": 12.5,
  "cpu_model": "Intel Core i7",
  "ram_percent": 63.2,
  "ram_total": 17179869184,
  "disk_percent": 71.8,
  "disk_total": 512110190592,
  "temp_c": 45.3
}
```

### Campos

- `timestamp`: instante en Unix epoch.
- `cpu_percent`: porcentaje de uso de CPU.
- `cpu_model`: modelo del procesador.
- `ram_percent`: porcentaje de RAM usada.
- `ram_total`: total de RAM en bytes.
- `disk_percent`: porcentaje de disco usado.
- `disk_total`: total de disco en bytes.
- `temp_c`: temperatura en grados Celsius. Puede ser `null` si no se pudo detectar.

## Cómo funciona la temperatura

La lectura de temperatura es mejor esfuerzo:

- Primero intenta obtener sensores con `gopsutil`.
- Si está en Windows, intenta consultas WMI mediante PowerShell.
- Si no encuentra un valor válido, deja `temp_c` en `null`.

Esto significa que la temperatura puede no estar disponible en todos los equipos, especialmente si el hardware o los permisos no permiten acceder al sensor.

## Configuración

El script acepta este parámetro:

- `-kafka-brokers`: Lista separada por comas de los brokers de Kafka.

Valor por defecto:

```text
localhost:9094
```

## Ejecución

Desde la carpeta del proyecto:

### Lanzar en modo publicador (envía métricas de forma continua cada 5 minutos)

```bash
go run SubscriberKafka.go ConsumerKafka.go metrics_shared.go -mode=publisher -kafka-brokers="localhost:9094"
```

### Lanzar en modo consumidor (para depuración)

```bash
go run SubscriberKafka.go ConsumerKafka.go metrics_shared.go -mode=consumer -kafka-brokers="localhost:9094"
```

También puedes compilarlo:

```bash
go build -o it-monitor.exe SubscriberKafka.go ConsumerKafka.go metrics_shared.go
```

Y ejecutar el binario generado:

```bash
.\it-monitor.exe -kafka-brokers="localhost:9094"
```

## Qué publica exactamente

El script:

- Abre una conexión con Kafka.
- Publica el payload binario codificado con Avro en el topic `telemetry`.

## Consideraciones importantes

- El script en modo publisher se ejecuta de forma continua enviando métricas a intervalos fijos de 5 minutos.
- La lectura de disco usa el path raíz que resuelve la librería del sistema.
- Si Kafka no está disponible, el script registrará los fallos de envío en consola.
- Si la temperatura no puede obtenerse, el resto de métricas sigue enviándose normalmente.

## Ejemplo de uso esperado

1. Iniciar el broker de Kafka.
2. Ejecutar el script.
3. Verificar que el topic `telemetry` reciba el mensaje.
