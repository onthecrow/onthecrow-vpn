package main

import (
	"encoding/json"
	"os"

	"github.com/xtls/libxray/xray"
)

// Platform flag names, spelled out rather than imported from xray-core's
// `common/platform` so the sidecar keeps depending only on libXray's own
// surface. They are part of xray-core's public configuration contract.
const (
	assetLocationKey = "xray.location.asset"
	certLocationKey  = "xray.location.cert"
	mphCachePathKey  = "xray.mph.cache"
)

type runXrayConfig struct {
	// tun
	TunName     string `json:"tunName,omitempty"`
	TunPriority int    `json:"tunPriority,omitempty"`
	// dns
	Dns           string `json:"dns,omitempty"`
	BindInterface string `json:"bindInterface,omitempty"`
	// xray
	DatDir       string `json:"datDir,omitempty"`
	MphCachePath string `json:"mphCachePath,omitempty"`
	ConfigPath   string `json:"configPath,omitempty"`
}

func runXray(configPath string) error {
	configBytes, err := os.ReadFile(configPath)
	if err != nil {
		return err
	}
	var config runXrayConfig
	err = json.Unmarshal(configBytes, &config)
	if err != nil {
		return err
	}

	// RunXray used to take datDir and mphCachePath and call os.Setenv itself; it
	// now takes the config path alone, so the sidecar sets the flags. os.Setenv
	// (not the C setenv) is what matters here — Go reads its own copy of the
	// environment, taken when the process started.
	if config.DatDir != "" {
		if err := os.Setenv(assetLocationKey, config.DatDir); err != nil {
			return err
		}
		if err := os.Setenv(certLocationKey, config.DatDir); err != nil {
			return err
		}
	}
	if config.MphCachePath != "" {
		if err := os.Setenv(mphCachePathKey, config.MphCachePath); err != nil {
			return err
		}
	}

	err = xray.RunXray(config.ConfigPath)
	if err != nil {
		return err
	}
	// The `dns` field is still accepted for compatibility with configs already on
	// disk, but no longer acted on: libXray's dns package is gone, and what it did
	// on our two targets was nothing. macOS took the no-op build, and Windows bound
	// the Go resolver to `bindInterface`, which we never set — with an empty name
	// the lookup failed and the socket option was skipped. Xray-core resolves
	// through its own DNS either way.
	return initIpRoute(config.TunName, config.TunPriority)
}

func stopXray() {
	_ = xray.StopXray()
}
