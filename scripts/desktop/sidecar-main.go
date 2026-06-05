package main

// Patched drop-in replacement for libXray's desktop_bin/main.go.
// The only change vs upstream is printing the runXray error to stderr before
// exiting — upstream does a silent os.Exit(1), which makes desktop failures
// impossible to diagnose. Copied over desktop_bin/main.go by
// scripts/build-libxray-desktop.sh before building.

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
