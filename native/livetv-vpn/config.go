// Meowio Live TV VPN: a WireGuard tunnel that lives inside the app and is used for Live TV
// playback only. It never becomes a system VPN, so no other app traffic (debrid above all)
// can pass through it.
//
// The idea of scoping a VPN to playback through a userspace WireGuard tunnel exposed as a local
// authenticated proxy comes from the VPN that ghbarker contributed to NuvioPlusIPTV
// (https://github.com/homelessbrian/NuvioPlusIPTV, GPL-3.0). This is a smaller rewrite of it.
package main

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

type peer struct {
	publicKey    string
	presharedKey string
	endpoint     string
	allowedIPs   []string
	keepalive    int
}

type wgConfig struct {
	privateKey string
	addresses  []netip.Addr
	dns        []netip.Addr
	mtu        int
	peers      []peer
}

func keyHex(value string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(value))
	if err != nil || len(raw) != 32 {
		return "", errors.New("a key in the WireGuard file is not valid")
	}
	return hex.EncodeToString(raw), nil
}

// parseConfig reads a wg-quick style file as VPN providers hand them out.
func parseConfig(text string) (*wgConfig, error) {
	cfg := &wgConfig{mtu: 1280}
	section := ""
	var current *peer
	scanner := bufio.NewScanner(strings.NewReader(text))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if i := strings.IndexAny(line, "#;"); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[] "))
			if section == "peer" {
				cfg.peers = append(cfg.peers, peer{})
				current = &cfg.peers[len(cfg.peers)-1]
			}
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.ToLower(strings.TrimSpace(key))
		value = strings.TrimSpace(value)
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				hexKey, err := keyHex(value)
				if err != nil {
					return nil, err
				}
				cfg.privateKey = hexKey
			case "address":
				for _, part := range splitList(value) {
					prefix, err := netip.ParsePrefix(part)
					if err != nil {
						addr, err2 := netip.ParseAddr(part)
						if err2 != nil {
							return nil, fmt.Errorf("address %q is not valid", part)
						}
						cfg.addresses = append(cfg.addresses, addr)
						continue
					}
					cfg.addresses = append(cfg.addresses, prefix.Addr())
				}
			case "dns":
				for _, part := range splitList(value) {
					if addr, err := netip.ParseAddr(part); err == nil {
						cfg.dns = append(cfg.dns, addr)
					}
				}
			case "mtu":
				if mtu, err := strconv.Atoi(value); err == nil && mtu >= 576 && mtu <= 9000 {
					cfg.mtu = mtu
				}
			}
		case "peer":
			if current == nil {
				continue
			}
			switch key {
			case "publickey":
				hexKey, err := keyHex(value)
				if err != nil {
					return nil, err
				}
				current.publicKey = hexKey
			case "presharedkey":
				hexKey, err := keyHex(value)
				if err != nil {
					return nil, err
				}
				current.presharedKey = hexKey
			case "endpoint":
				current.endpoint = value
			case "allowedips":
				current.allowedIPs = append(current.allowedIPs, splitList(value)...)
			case "persistentkeepalive":
				if seconds, err := strconv.Atoi(value); err == nil && seconds >= 0 {
					current.keepalive = seconds
				}
			}
		}
	}
	if cfg.privateKey == "" {
		return nil, errors.New("the WireGuard file has no PrivateKey")
	}
	if len(cfg.addresses) == 0 {
		return nil, errors.New("the WireGuard file has no Address")
	}
	if len(cfg.peers) == 0 || cfg.peers[0].publicKey == "" || cfg.peers[0].endpoint == "" {
		return nil, errors.New("the WireGuard file needs a [Peer] with PublicKey and Endpoint")
	}
	if len(cfg.dns) == 0 {
		cfg.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("9.9.9.9")}
	}
	return cfg, nil
}

func splitList(value string) []string {
	var out []string
	for _, part := range strings.Split(value, ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}

// uapi turns the config into WireGuard's configuration protocol. Endpoints are looked up on the
// normal network here, because the tunnel cannot resolve names before it is up.
func (cfg *wgConfig) uapi() (string, error) {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", cfg.privateKey)
	for _, p := range cfg.peers {
		if p.publicKey == "" || p.endpoint == "" {
			continue
		}
		host, port, err := net.SplitHostPort(p.endpoint)
		if err != nil {
			return "", fmt.Errorf("endpoint %q is not host:port", p.endpoint)
		}
		ip, err := netip.ParseAddr(host)
		if err != nil {
			ips, lookupErr := net.LookupIP(host)
			if lookupErr != nil || len(ips) == 0 {
				return "", fmt.Errorf("could not find the VPN server %s", host)
			}
			chosen := ips[0]
			for _, candidate := range ips {
				if candidate.To4() != nil {
					chosen = candidate
					break
				}
			}
			ip, _ = netip.AddrFromSlice(chosen)
			ip = ip.Unmap()
		}
		fmt.Fprintf(&b, "public_key=%s\n", p.publicKey)
		if p.presharedKey != "" {
			fmt.Fprintf(&b, "preshared_key=%s\n", p.presharedKey)
		}
		fmt.Fprintf(&b, "endpoint=%s\n", netip.AddrPortFrom(ip, mustPort(port)).String())
		if p.keepalive > 0 {
			fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", p.keepalive)
		}
		allowed := p.allowedIPs
		if len(allowed) == 0 {
			allowed = []string{"0.0.0.0/0", "::/0"}
		}
		for _, a := range allowed {
			fmt.Fprintf(&b, "allowed_ip=%s\n", a)
		}
	}
	return b.String(), nil
}

func mustPort(text string) uint16 {
	port, err := strconv.Atoi(text)
	if err != nil || port < 1 || port > 65535 {
		return 51820
	}
	return uint16(port)
}
