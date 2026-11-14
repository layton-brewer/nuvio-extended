package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// tunnel is one WireGuard connection plus a proxy on 127.0.0.1 that sends what it is given
// through it. Only callers that know the random token can use the proxy.
type tunnel struct {
	dev      *device.Device
	net      *netstack.Net
	listener net.Listener
	token    string
	client   *http.Client
	closeOne sync.Once
}

func randomToken() string {
	buf := make([]byte, 24)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}

func startTunnel(configText string) (*tunnel, error) {
	cfg, err := parseConfig(configText)
	if err != nil {
		return nil, err
	}
	setting, err := cfg.uapi()
	if err != nil {
		return nil, err
	}
	tunDev, tnet, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return nil, err
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	if err := dev.IpcSet(setting); err != nil {
		dev.Close()
		return nil, errors.New("the WireGuard settings were not accepted")
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, err
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		dev.Close()
		return nil, err
	}
	t := &tunnel{dev: dev, net: tnet, listener: listener, token: randomToken()}
	transport := &http.Transport{
		DialContext:           tnet.DialContext,
		Proxy:                 nil,
		MaxIdleConnsPerHost:   8,
		IdleConnTimeout:       60 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		DisableCompression:    true,
	}
	t.client = &http.Client{
		Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			// Hand redirects back to the caller, which decides where they may go.
			return http.ErrUseLastResponse
		},
	}
	server := &http.Server{Handler: t, ReadHeaderTimeout: 20 * time.Second}
	go func() { _ = server.Serve(listener) }()
	return t, nil
}

func (t *tunnel) port() int { return t.listener.Addr().(*net.TCPAddr).Port }

func (t *tunnel) close() {
	t.closeOne.Do(func() {
		_ = t.listener.Close()
		t.dev.Close()
	})
}

func (t *tunnel) authorized(r *http.Request) bool {
	header := r.Header.Get("Proxy-Authorization")
	if !strings.HasPrefix(header, "Basic ") {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(header, "Basic "))
	if err != nil {
		return false
	}
	_, password, _ := strings.Cut(string(raw), ":")
	return subtle.ConstantTimeCompare([]byte(password), []byte(t.token)) == 1
}

func (t *tunnel) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !t.authorized(r) {
		w.Header().Set("Proxy-Authenticate", `Basic realm="meowio"`)
		w.WriteHeader(http.StatusProxyAuthRequired)
		return
	}
	if r.Method == http.MethodConnect {
		t.connect(w, r)
		return
	}
	if r.URL == nil || !r.URL.IsAbs() || (r.URL.Scheme != "http" && r.URL.Scheme != "https") {
		http.Error(w, "only proxy requests", http.StatusBadRequest)
		return
	}
	out := r.Clone(r.Context())
	out.RequestURI = ""
	out.Header.Del("Proxy-Authorization")
	out.Header.Del("Proxy-Connection")
	resp, err := t.client.Do(out)
	if err != nil {
		http.Error(w, "the VPN could not reach the server", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	for key, values := range resp.Header {
		for _, value := range values {
			w.Header().Add(key, value)
		}
	}
	w.WriteHeader(resp.StatusCode)
	copyFlushing(w, resp.Body)
}

func copyFlushing(w http.ResponseWriter, body io.Reader) {
	flusher, _ := w.(http.Flusher)
	buf := make([]byte, 64*1024)
	for {
		n, err := body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if err != nil {
			return
		}
	}
}

// connect opens a raw connection through the tunnel, which HTTPS requests use.
func (t *tunnel) connect(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	upstream, err := t.net.DialContext(ctx, "tcp", r.Host)
	cancel()
	if err != nil {
		http.Error(w, "the VPN could not reach the server", http.StatusBadGateway)
		return
	}
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		upstream.Close()
		http.Error(w, "unsupported", http.StatusInternalServerError)
		return
	}
	client, buffered, err := hijacker.Hijack()
	if err != nil {
		upstream.Close()
		return
	}
	_, _ = client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	go pipe(upstream, buffered)
	go func() {
		_, _ = io.Copy(client, upstream)
		client.Close()
		upstream.Close()
	}()
}

func pipe(dst net.Conn, src *bufio.ReadWriter) {
	_, _ = io.Copy(dst, src)
	dst.Close()
}
