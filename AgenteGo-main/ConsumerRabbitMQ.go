package main

import (
	"fmt"
	"log"

	"github.com/hamba/avro/v2"
	"github.com/rabbitmq/amqp091-go"
)

func runConsumer(rabbitMQURL string) {
	conn, err := amqp091.Dial(rabbitMQURL)
	if err != nil {
		log.Fatalf("Error conectando a RabbitMQ: %v", err)
	}
	defer conn.Close()

	ch, err := conn.Channel()
	if err != nil {
		log.Fatalf("Error abriendo canal: %v", err)
	}
	defer ch.Close()

	// Declaramos el exchange
	err = ch.ExchangeDeclare(
		"telemetry_fanout", // name
		"fanout",           // type
		true,               // durable
		false,              // auto-deleted
		false,              // internal
		false,              // no-wait
		nil,                // arguments
	)
	if err != nil {
		log.Fatalf("Error declarando exchange: %v", err)
	}

	// Declaramos la cola igual que en el publisher
	q, err := ch.QueueDeclare("it_metrics", false, false, false, false, nil)
	if err != nil {
		log.Fatalf("Error declarando cola: %v", err)
	}

	// Vinculamos la cola al exchange
	err = ch.QueueBind(
		q.Name,             // queue name
		"",                 // routing key
		"telemetry_fanout", // exchange
		false,
		nil,
	)
	if err != nil {
		log.Fatalf("Error vinculando cola al exchange: %v", err)
	}

	msgs, err := ch.Consume(
		q.Name, // queue
		"",     // consumer
		true,   // auto-ack
		false,  // exclusive
		false,  // no-local
		false,  // no-wait
		nil,    // args
	)
	if err != nil {
		log.Fatalf("Error registrando el consumidor: %v", err)
	}

	var forever chan struct{}

	go func() {
		for d := range msgs {
			var payload Metrics
			err := avro.Unmarshal(metricsSchema, d.Body, &payload)
			if err != nil {
				log.Printf("Error deserializando Avro: %v", err)
				continue
			}
			fmt.Printf("✅ Mensaje recibido en RabbitMQ y decodificado correctamente:\n%+v\n\n", payload)
		}
	}()

	log.Printf(" [*] Escuchando mensajes en la cola '%s'. Para salir pulsa CTRL+C", q.Name)
	<-forever // Mantiene el programa corriendo de forma indefinida
}
