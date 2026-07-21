package main

// The desktop sidecar: creates the OS TUN device and runs Xray-core in it.
//
// This is libXray's desktop_bin, which upstream deleted in v26.7.11 along with
// the dns package it used. We keep it, because it is the whole Windows VPN. The
// sources here are the upstream ones at v26.3.27 with two changes: this file
// prints the runXray error to stderr before exiting (upstream does a silent
// os.Exit(1), which makes desktop failures impossible to diagnose), and run.go
// is adapted to the new xray.RunXray signature — see the notes there.
//
// route_linux.go is deliberately not vendored: it pulls in netlink, and the
// build script only targets macOS and Windows. Adding a Linux target means
// bringing that file back too.
//
// scripts/build-libxray-desktop.sh copies this directory into the libXray
// module tree, where it can import the internal packages, and builds it.

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"runtime"
	"runtime/debug"
	"syscall"
)

func main() {
	configPath := flag.String("configPath", "config.json", "Path of config.json")
	flag.Parse()
	err := runXray(*configPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "runXray error:", err)
		os.Exit(1)
	}
	defer stopXray()
	runtime.GC()
	debug.FreeOSMemory()

	{
		osSignals := make(chan os.Signal, 1)
		signal.Notify(osSignals, os.Interrupt, syscall.SIGTERM)
		<-osSignals
	}
}
