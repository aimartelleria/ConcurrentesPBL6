package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/hamba/avro/v2"
	"github.com/segmentio/kafka-go"
	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/host"
	"github.com/shirou/gopsutil/v3/mem"
)

// getTemperature intenta obtener la temperatura de CPU desde múltiples fuentes.
func getTemperature() *float64 {
	// Try gopsutil sensors
	t, _ := host.SensorsTemperatures()
	if len(t) > 0 {
		v := t[0].Temperature
		if v > -50 && v < 150 { // Validar rango realista
			return &v
		}
	}

	// En Windows, intentamos PowerShell + WMI
	if runtime.GOOS == "windows" {
		return getTemperatureViaPowerShell()
	}

	return nil
}

// getTemperatureViaPowerShell consulta WMI vía PowerShell para máxima compatibilidad.
func getTemperatureViaPowerShell() *float64 {
	// Query que busca MSAcpi_ThermalZoneTemperature (thermal zones del chipset).
	// Valor en décimas de Kelvin, convertir a Celsius.
	psCmd := `Get-WmiObject -Namespace "root\wmi" -Class MSAcpi_ThermalZoneTemperature -ErrorAction SilentlyContinue | Select-Object -First 1 | ForEach-Object { $_.CurrentTemperature }`

	cmd := exec.Command("powershell", "-NoProfile", "-Command", psCmd)
	output, err := cmd.Output()
	if err == nil && len(strings.TrimSpace(string(output))) > 0 {
		if val, parseErr := strconv.ParseFloat(strings.TrimSpace(string(output)), 64); parseErr == nil {
			// Convertir décimas de Kelvin a Celsius
			tempC := val/10.0 - 273.15
			if tempC > -50 && tempC < 150 { // Rango realista
				return &tempC
			}
		}
	}

	// Fallback: intenta Win32_SystemEnclosure
	psCmd = `Get-WmiObject Win32_SystemEnclosure -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty HotSwappable`
	cmd = exec.Command("powershell", "-NoProfile", "-Command", psCmd)
	output, err = cmd.Output()
	if err == nil && len(strings.TrimSpace(string(output))) > 0 {
		if val, parseErr := strconv.ParseFloat(strings.TrimSpace(string(output)), 64); parseErr == nil && val > -50 && val < 150 {
			return &val
		}
	}

	// Último fallback: busca cualquier temperatura disponible en Win32_TemperatureProbe
	psCmd = `Get-WmiObject Win32_TemperatureProbe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty CurrentReading`
	cmd = exec.Command("powershell", "-NoProfile", "-Command", psCmd)
	output, err = cmd.Output()
	if err == nil && len(strings.TrimSpace(string(output))) > 0 {
		if val, parseErr := strconv.ParseFloat(strings.TrimSpace(string(output)), 64); parseErr == nil {
			// Si el valor es muy grande, asumimos que está en décimas
			if val > 1000 {
				val = val / 10.0
			}
			if val > -50 && val < 150 {
				return &val
			}
		}
	}

	return nil
}

func sendMetrics(kafkaBrokers string) {
	// 1. Recolección de datos
	c, err := cpu.Percent(time.Second, false)
	if err != nil {
		log.Printf("Error obteniendo CPU: %v", err)
		return
	}

	cpuInfo, err := cpu.Info()
	var cpuModel string
	if err != nil || len(cpuInfo) == 0 {
		cpuModel = "Unknown"
	} else {
		cpuModel = cpuInfo[0].ModelName
	}

	m, err := mem.VirtualMemory()
	if err != nil {
		log.Printf("Error obteniendo RAM: %v", err)
		return
	}

	d, err := disk.Usage("/")
	if err != nil {
		log.Printf("Error obteniendo Disco: %v", err)
		return
	}

	currentTemp := getTemperature()

	payload := Metrics{
		Timestamp:   time.Now().Unix(),
		CPUPercent:  c[0],
		CPUModel:    cpuModel,
		RAMPercent:  m.UsedPercent,
		RAMTotal:    int64(m.Total),
		DiskPercent: d.UsedPercent,
		DiskTotal:   int64(d.Total),
		Temp:        currentTemp,
	}

	// Printear mensaje antes de serializar
	fmt.Printf("[%s] Datos recolectados normales: %+v\n", time.Now().Format("2006-01-02 15:04:05"), payload)

	body, err := avro.Marshal(metricsSchema, payload)
	if err != nil {
		log.Printf("Error al serializar a Avro: %v", err)
		return
	}

	// 2. Envío a Kafka
	w := &kafka.Writer{
		Addr:     kafka.TCP(kafkaBrokers),
		Topic:    "telemetry",
		Balancer: &kafka.LeastBytes{},
	}
	defer w.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = w.WriteMessages(ctx,
		kafka.Message{
			Value: body,
		},
	)

	if err != nil {
		log.Printf("Error al enviar mensaje a Kafka: %v", err)
	} else {
		fmt.Printf("[%s] datos binarios enviados correctamente por Kafka\n", time.Now().Format("2006-01-02 15:04:05"))
	}
}

func runPublisher(kafkaBrokers string) {
	fmt.Println("Iniciando servicio de recolección métricas en segundo plano...")
	fmt.Println("Se enviarán métricas a Kafka cada 5 minutos.")
	fmt.Println("Para detener el servicio, mata o finaliza el proceso desde el 'Administrador de Tareas'.")

	// Enviar primer lote de métricas de inmediato
	sendMetrics(kafkaBrokers)

	// Crear ticker para intervalos de 5 minutos
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	// Ciclo 'infinito' bloqueante
	for range ticker.C {
		sendMetrics(kafkaBrokers)
	}
}

func main() {
	mode := flag.String("mode", "publisher", "Mode to run: 'publisher' or 'consumer'")
	kafkaBrokers := flag.String("kafka-brokers", "localhost:9094", "Kafka brokers comma-separated list")
	flag.Parse()

	if *mode == "consumer" {
		runConsumer(*kafkaBrokers)
	} else {
		runPublisher(*kafkaBrokers)
	}
}
