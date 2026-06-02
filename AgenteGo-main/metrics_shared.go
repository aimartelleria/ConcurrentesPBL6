package main

import (
	"log"

	"github.com/hamba/avro/v2"
)

type Metrics struct {
	Timestamp   int64    `avro:"timestamp" json:"timestamp"`
	CPUPercent  float64  `avro:"cpu_percent" json:"cpu_percent"`
	CPUModel    string   `avro:"cpu_model" json:"cpu_model"`
	RAMPercent  float64  `avro:"ram_percent" json:"ram_percent"`
	RAMTotal    int64    `avro:"ram_total" json:"ram_total"`
	DiskPercent float64  `avro:"disk_percent" json:"disk_percent"`
	DiskTotal   int64    `avro:"disk_total" json:"disk_total"`
	Temp        *float64 `avro:"temp_c" json:"temp_c"`
}

var metricsSchema avro.Schema

func initMetricsSchema() {
	schemaStr := `{
		"type": "record",
		"name": "Metrics",
		"namespace": "com.example",
		"fields": [
			{"name": "timestamp", "type": "long"},
			{"name": "cpu_percent", "type": "double"},
			{"name": "cpu_model", "type": "string"},
			{"name": "ram_percent", "type": "double"},
			{"name": "ram_total", "type": "long"},
			{"name": "disk_percent", "type": "double"},
			{"name": "disk_total", "type": "long"},
			{"name": "temp_c", "type": ["null", "double"], "default": null}
		]
	}`
	var err error
	metricsSchema, err = avro.Parse(schemaStr)
	if err != nil {
		log.Fatalf("Error parsing avro schema: %v", err)
	}
}

func init() {
	initMetricsSchema()
}
