// Package sink publishes ad events to Kafka.
package sink

import (
	"context"
	"encoding/json"
	"log/slog"
	"time"

	"github.com/segmentio/kafka-go"

	"github.com/mrasheed-cse/adcp-platform/pkg/events"
)

// Kafka batches events and writes them asynchronously so the pixel response never waits on Kafka.
// Messages are keyed by request id, so an impression and its click land on the same partition.
type Kafka struct {
	w   *kafka.Writer
	log *slog.Logger
}

func NewKafka(brokers []string, topic string, log *slog.Logger) *Kafka {
	k := &Kafka{log: log}
	k.w = &kafka.Writer{
		Addr:                   kafka.TCP(brokers...),
		Topic:                  topic,
		Balancer:               &kafka.Hash{},
		BatchTimeout:           20 * time.Millisecond,
		RequiredAcks:           kafka.RequireOne,
		Async:                  true,
		AllowAutoTopicCreation: true,
		Completion: func(msgs []kafka.Message, err error) {
			if err != nil {
				log.Error("kafka write failed", "err", err, "events", len(msgs))
			}
		},
	}
	return k
}

func (k *Kafka) Publish(e events.Event) {
	b, err := json.Marshal(e)
	if err != nil {
		k.log.Error("encode event", "err", err)
		return
	}
	// Async writer: returns immediately; delivery errors arrive in Completion.
	if err := k.w.WriteMessages(context.Background(), kafka.Message{Key: []byte(e.RequestID), Value: b}); err != nil {
		k.log.Error("queue event", "err", err)
	}
}

// Close flushes buffered events.
func (k *Kafka) Close() error { return k.w.Close() }
