// onthecrow-convert: reads a single share link (vless:// / hysteria2:// / ...)
// from stdin and writes the equivalent Xray JSON config to stdout.
//
// Reuses libXray's own share-link parser so desktop conversion is byte-identical
// to the mobile (Android AAR) path. Pure parsing, no privileges, no network.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/xtls/libxray/share"
)

func main() {
	raw, err := io.ReadAll(os.Stdin)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read stdin:", err)
		os.Exit(1)
	}
	link := strings.TrimSpace(string(raw))
	if link == "" {
		fmt.Fprintln(os.Stderr, "empty input")
		os.Exit(1)
	}
	cfg, err := share.ConvertShareLinksToXrayJson(link)
	if err != nil {
		fmt.Fprintln(os.Stderr, "convert:", err)
		os.Exit(1)
	}
	out, err := json.Marshal(cfg)
	if err != nil {
		fmt.Fprintln(os.Stderr, "marshal:", err)
		os.Exit(1)
	}
	_, _ = os.Stdout.Write(out)
}
