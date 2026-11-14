package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"testing"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func keyPair(t *testing.T) (private, public []byte) {
	private = make([]byte, 32)
	if _, err := rand.Read(private); err != nil {
		t.Fatal(err)
	}
	private[0] &= 248
	private[31] = (private[31] & 127) | 64
	public, err := curve25519.X25519(private, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return private, public
}

// A WireGuard "server" inside the test, with a web server reachable only through the tunnel.
func TestProxySendsTrafficThroughTheTunnel(t *testing.T) {
	serverPriv, serverPub := keyPair(t)
	clientPriv, clientPub := keyPair(t)

	tunDev, tnet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.9.0.1")}, nil, 1420)
	if err != nil {
		t.Fatal(err)
	}
	server := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
	defer server.Close()
	udp, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	port := udp.LocalAddr().(*net.UDPAddr).Port
	udp.Close()
	if err := server.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.9.0.2/32\n",
		hex.EncodeToString(serverPriv), port, hex.EncodeToString(clientPub))); err != nil {
		t.Fatal(err)
	}
	if err := server.Up(); err != nil {
		t.Fatal(err)
	}
	listener, err := tnet.ListenTCP(&net.TCPAddr{Port: 80})
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(listener, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "through the tunnel")
	}))

	config := fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = 10.9.0.2/32
DNS = 10.9.0.1

[Peer]
PublicKey = %s
Endpoint = 127.0.0.1:%d
AllowedIPs = 10.9.0.0/24
`, base64.StdEncoding.EncodeToString(clientPriv), base64.StdEncoding.EncodeToString(serverPub), port)
	tun, err := startTunnel(config)
	if err != nil {
		t.Fatal(err)
	}
	defer tun.close()

	proxyURL, _ := url.Parse(fmt.Sprintf("http://meowio:%s@127.0.0.1:%d", tun.token, tun.port()))
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}
	resp, err := client.Get("http://10.9.0.1/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "through the tunnel" {
		t.Fatalf("got %q", body)
	}

	// Without the password the proxy refuses.
	wrong, _ := url.Parse(fmt.Sprintf("http://meowio:nope@127.0.0.1:%d", tun.port()))
	refused := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(wrong)}}
	resp, err = refused.Get("http://10.9.0.1/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("expected 407, got %d", resp.StatusCode)
	}
}

func TestParseConfigRejectsIncompleteFiles(t *testing.T) {
	if _, err := parseConfig("[Interface]\nAddress = 10.0.0.2/32\n"); err == nil || !strings.Contains(err.Error(), "PrivateKey") {
		t.Fatalf("expected a PrivateKey error, got %v", err)
	}
	priv, pub := keyPair(t)
	cfg, err := parseConfig(fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.0.0.2/32, fd00::2/128\n[Peer]\nPublicKey = %s\nEndpoint = 1.2.3.4:51820\n",
		base64.StdEncoding.EncodeToString(priv), base64.StdEncoding.EncodeToString(pub)))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.addresses) != 2 || len(cfg.dns) == 0 {
		t.Fatalf("unexpected config %+v", cfg)
	}
}
