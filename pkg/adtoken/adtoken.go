// Package adtoken signs and verifies the tokens carried in impression and click tracking URLs.
// The decision engine signs; the event collector verifies. A forged or altered token is rejected,
// so nobody can inflate impressions or redirect clicks to an arbitrary site.
package adtoken

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strings"
	"time"
)

// MinKeyLen is the minimum secret length in bytes.
const MinKeyLen = 32

// Claims describe one served ad. Short JSON names keep tracking URLs compact.
type Claims struct {
	RequestID   string  `json:"rid"`
	LineItemID  string  `json:"li"`
	CreativeID  string  `json:"cr"`
	PlacementID string  `json:"pl"`
	PublisherID string  `json:"pub"`
	UserKey     string  `json:"uk,omitempty"` // pseudonymous user key, never the raw id
	CPM         float64 `json:"cpm"`
	Country     string  `json:"c,omitempty"`
	Device      string  `json:"d,omitempty"`
	LandingURL  string  `json:"lp"`
	FreqWindow  int64   `json:"fw,omitempty"` // frequency-cap window in seconds, 0 = no cap
	IssuedAt    int64   `json:"iat"`          // unix seconds
}

var (
	ErrMalformed = errors.New("adtoken: malformed token")
	ErrSignature = errors.New("adtoken: bad signature")
	ErrExpired   = errors.New("adtoken: token expired")
	ErrFuture    = errors.New("adtoken: token issued in the future")
)

// clockSkew tolerates small clock differences between servers.
const clockSkew = 5 * time.Minute

type Signer struct {
	signKey []byte
	userKey []byte
}

// NewSigner derives separate keys for signing and for user pseudonymization from one secret.
func NewSigner(secret []byte) (*Signer, error) {
	if len(secret) < MinKeyLen {
		return nil, errors.New("adtoken: secret must be at least 32 bytes")
	}
	return &Signer{signKey: derive(secret, "token-signing"), userKey: derive(secret, "user-key")}, nil
}

func derive(secret []byte, label string) []byte {
	m := hmac.New(sha256.New, secret)
	m.Write([]byte(label))
	return m.Sum(nil)
}

// UserKey turns a raw user id (cookie or device id) into a stable pseudonymous key.
// Empty input returns empty output.
func (s *Signer) UserKey(rawUserID string) string {
	if rawUserID == "" {
		return ""
	}
	m := hmac.New(sha256.New, s.userKey)
	m.Write([]byte(rawUserID))
	return hex.EncodeToString(m.Sum(nil))[:32]
}

func (s *Signer) Sign(c Claims) (string, error) {
	raw, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	payload := base64.RawURLEncoding.EncodeToString(raw)
	return payload + "." + base64.RawURLEncoding.EncodeToString(s.mac(payload)), nil
}

// Verify checks the signature and age. maxAge bounds how long after serving an event is accepted.
func (s *Signer) Verify(token string, now time.Time, maxAge time.Duration) (Claims, error) {
	var c Claims
	payload, sig, ok := strings.Cut(token, ".")
	if !ok || payload == "" || sig == "" {
		return c, ErrMalformed
	}
	gotMAC, err := base64.RawURLEncoding.DecodeString(sig)
	if err != nil {
		return c, ErrMalformed
	}
	if !hmac.Equal(gotMAC, s.mac(payload)) {
		return c, ErrSignature
	}
	raw, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil {
		return c, ErrMalformed
	}
	if err := json.Unmarshal(raw, &c); err != nil {
		return Claims{}, ErrMalformed
	}
	issued := time.Unix(c.IssuedAt, 0)
	if issued.After(now.Add(clockSkew)) {
		return Claims{}, ErrFuture
	}
	if now.Sub(issued) > maxAge {
		return Claims{}, ErrExpired
	}
	return c, nil
}

func (s *Signer) mac(payload string) []byte {
	m := hmac.New(sha256.New, s.signKey)
	m.Write([]byte(payload))
	return m.Sum(nil)
}
