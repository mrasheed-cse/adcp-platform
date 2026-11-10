package adtoken

import (
	"errors"
	"strings"
	"testing"
	"time"
)

var secret = []byte("0123456789abcdef0123456789abcdef")

func newSigner(t *testing.T) *Signer {
	t.Helper()
	s, err := NewSigner(secret)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestRoundTrip(t *testing.T) {
	s := newSigner(t)
	now := time.Unix(1_800_000_000, 0)
	in := Claims{RequestID: "r1", LineItemID: "li1", CreativeID: "cr1", CPM: 2.5,
		LandingURL: "https://example.com/?a=1&b=2", IssuedAt: now.Unix()}
	tok, err := s.Sign(in)
	if err != nil {
		t.Fatal(err)
	}
	out, err := s.Verify(tok, now.Add(time.Minute), time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if out != in {
		t.Fatalf("got %+v want %+v", out, in)
	}
}

func TestTamperedPayloadRejected(t *testing.T) {
	s := newSigner(t)
	now := time.Now()
	tok, _ := s.Sign(Claims{RequestID: "r1", LineItemID: "li1", IssuedAt: now.Unix()})
	other, _ := s.Sign(Claims{RequestID: "r1", LineItemID: "li2", IssuedAt: now.Unix()})
	p1, _, _ := strings.Cut(tok, ".")
	_, sig2, _ := strings.Cut(other, ".")
	if _, err := s.Verify(p1+"."+sig2, now, time.Hour); !errors.Is(err, ErrSignature) {
		t.Fatalf("want ErrSignature, got %v", err)
	}
}

func TestWrongKeyRejected(t *testing.T) {
	s := newSigner(t)
	other, _ := NewSigner([]byte("ffffffffffffffffffffffffffffffff"))
	now := time.Now()
	tok, _ := other.Sign(Claims{RequestID: "r1", IssuedAt: now.Unix()})
	if _, err := s.Verify(tok, now, time.Hour); !errors.Is(err, ErrSignature) {
		t.Fatalf("want ErrSignature, got %v", err)
	}
}

func TestExpiryAndFuture(t *testing.T) {
	s := newSigner(t)
	now := time.Unix(1_800_000_000, 0)
	tok, _ := s.Sign(Claims{RequestID: "r1", IssuedAt: now.Unix()})
	if _, err := s.Verify(tok, now.Add(2*time.Hour), time.Hour); !errors.Is(err, ErrExpired) {
		t.Fatalf("want ErrExpired, got %v", err)
	}
	if _, err := s.Verify(tok, now.Add(-time.Hour), time.Hour); !errors.Is(err, ErrFuture) {
		t.Fatalf("want ErrFuture, got %v", err)
	}
}

func TestMalformed(t *testing.T) {
	s := newSigner(t)
	for _, tok := range []string{"", "abc", ".", "abc.", ".abc", "!!!.###"} {
		if _, err := s.Verify(tok, time.Now(), time.Hour); err == nil {
			t.Fatalf("token %q accepted", tok)
		}
	}
}

func TestShortSecretRejected(t *testing.T) {
	if _, err := NewSigner([]byte("short")); err == nil {
		t.Fatal("short secret accepted")
	}
}

func TestUserKeyStableAndPseudonymous(t *testing.T) {
	s := newSigner(t)
	a, b := s.UserKey("user-123"), s.UserKey("user-123")
	if a != b || len(a) != 32 || strings.Contains(a, "user-123") {
		t.Fatalf("unexpected user key %q / %q", a, b)
	}
	if s.UserKey("") != "" {
		t.Fatal("empty id should give empty key")
	}
	if s.UserKey("user-124") == a {
		t.Fatal("different ids collided")
	}
}
