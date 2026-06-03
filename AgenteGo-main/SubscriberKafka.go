package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"

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

// getBatteryPercent devuelve el % de batería (0-100) o nil si no hay batería
// (sobremesas/servidores) o no se puede leer. Mismo patrón que la temperatura:
// sysfs en Linux, WMI vía PowerShell en Windows. Sin dependencias externas.
func getBatteryPercent() *float64 {
	switch runtime.GOOS {
	case "linux":
		for _, name := range []string{"BAT0", "BAT1", "BAT2"} {
			data, err := os.ReadFile("/sys/class/power_supply/" + name + "/capacity")
			if err != nil {
				continue
			}
			if v, perr := strconv.ParseFloat(strings.TrimSpace(string(data)), 64); perr == nil && v >= 0 && v <= 100 {
				return &v
			}
		}
	case "windows":
		return getBatteryViaPowerShell()
	}
	return nil
}

// getBatteryViaPowerShell consulta Win32_Battery (EstimatedChargeRemaining es ya un %).
func getBatteryViaPowerShell() *float64 {
	psCmd := `Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty EstimatedChargeRemaining`
	cmd := exec.Command("powershell", "-NoProfile", "-Command", psCmd)
	output, err := cmd.Output()
	if err == nil && len(strings.TrimSpace(string(output))) > 0 {
		if v, perr := strconv.ParseFloat(strings.TrimSpace(string(output)), 64); perr == nil && v >= 0 && v <= 100 {
			return &v
		}
	}
	return nil
}

func sendMetrics(nifiURL, codigo, portatil string) {
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
	currentBat := getBatteryPercent()

	payload := Metrics{
		Timestamp:      time.Now().Unix(),
		CPUPercent:     c[0],
		CPUModel:       cpuModel,
		RAMPercent:     m.UsedPercent,
		RAMTotal:       int64(m.Total),
		DiskPercent:    d.UsedPercent,
		DiskTotal:      int64(d.Total),
		Temp:           currentTemp,
		BateriaPercent: currentBat,
	}

	// Printear mensaje antes de serializar
	fmt.Printf("[%s] Datos recolectados normales: %+v\n", time.Now().Format("2006-01-02 15:04:05"), payload)

	body, err := json.Marshal(payload)
	if err != nil {
		log.Printf("Error al serializar a JSON: %v", err)
		return
	}

	// 2. Envío al gateway NiFi (autenticación por licencia).
	// La telemetría va en JSON; NiFi valida la licencia contra MySQL, enriquece
	// con empresa_id+nombre y serializa a Avro (Schema Registry) antes de Kafka.
	// La licencia (codigo) y el nombre del ordenador viajan en cabeceras.
	req, err := http.NewRequest(http.MethodPost, nifiURL, bytes.NewReader(body))
	if err != nil {
		log.Printf("Error creando la petición HTTP a NiFi: %v", err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-License-Code", codigo)
	req.Header.Set("X-Portatil", portatil)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("Error al enviar telemetría a NiFi: %v", err)
		return
	}
	defer resp.Body.Close()

	ts := time.Now().Format("2006-01-02 15:04:05")
	switch {
	case resp.StatusCode >= 200 && resp.StatusCode < 300:
		fmt.Printf("[%s] telemetría aceptada por NiFi (HTTP %d)\n", ts, resp.StatusCode)
	case resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden:
		log.Printf("[%s] licencia RECHAZADA por NiFi (HTTP %d): revisa codigo/portatil o que activa = 1", ts, resp.StatusCode)
	default:
		log.Printf("[%s] NiFi respondió HTTP %d", ts, resp.StatusCode)
	}
}

func runPublisher(nifiURL, codigo, portatil string) {
	fmt.Println("Iniciando servicio de recolección métricas en segundo plano...")
	fmt.Printf("Se enviarán métricas al gateway NiFi (%s) cada 5 minutos.\n", nifiURL)
	fmt.Printf("Licencia: codigo=%s | portatil=%s\n", codigo, portatil)
	fmt.Println("Para detener el servicio, mata o finaliza el proceso desde el 'Administrador de Tareas'.")

	// Enviar primer lote de métricas de inmediato
	sendMetrics(nifiURL, codigo, portatil)

	// Crear ticker para intervalos de 5 minutos
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	// Ciclo 'infinito' bloqueante
	for range ticker.C {
		sendMetrics(nifiURL, codigo, portatil)
	}
}

func main() {
	mode := flag.String("mode", "publisher", "Modo: 'publisher' o 'consumer'")
	kafkaBrokers := flag.String("kafka-brokers", "localhost:9094", "Brokers de Kafka (solo modo consumer)")
	nifiURL := flag.String("nifi-url", "http://localhost:8081/telemetry", "Endpoint del gateway NiFi (ListenHTTP)")
	codigo := flag.String("codigo", "", "Código de licencia (API key del collector)")
	portatil := flag.String("portatil", "", "Identificador del portátil")
	flag.Parse()

	if *mode == "consumer" {
		runConsumer(*kafkaBrokers)
	} else {
		if *codigo == "" || *portatil == "" {
			log.Fatal("Faltan parámetros de licencia: usa -codigo y -portatil")
		}
		runPublisher(*nifiURL, *codigo, *portatil)
	}
}
