package main

import (
	"context"
	"fmt"
	"log"

	"github.com/hamba/avro/v2"
	"github.com/segmentio/kafka-go"
)

func runConsumer(kafkaBrokers string) {
	r := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  []string{kafkaBrokers},
		Topic:    "telemetry",
		GroupID:  "telemetry_go_consumers",
		MinBytes: 1,
		MaxBytes: 10e6, // 10MB
	})
	defer r.Close()

	log.Printf(" [*] Escuchando mensajes en el topic 'telemetry'. Para salir pulsa CTRL+C")

	for {
		m, err := r.ReadMessage(context.Background())
		if err != nil {
			log.Printf("Error leyendo mensaje de Kafka: %v", err)
			break
		}
		var payload Metrics
		err = avro.Unmarshal(metricsSchema, m.Value, &payload)
		if err != nil {
			log.Printf("Error deserializando Avro: %v", err)
			continue
		}
		fmt.Printf("✅ Mensaje recibido en Kafka y decodificado correctamente:\n%+v\n\n", payload)
	}
}
