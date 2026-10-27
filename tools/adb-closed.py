#!/usr/bin/env python3
"""Check that the TV no longer accepts ADB over the network.

  python tools/adb-closed.py 192.168.10.44 [port]

Only "connection refused" proves the port is closed. A timeout or "no route to host" can mean
the TV is off or a firewall/privacy feature on this computer blocked the attempt.
"""
import socket, sys

host = sys.argv[1] if len(sys.argv) > 1 else "192.168.10.44"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555
try:
    socket.create_connection((host, port), timeout=5).close()
    print(f"OPEN: {host}:{port} accepts connections - network ADB is still on.")
    sys.exit(1)
except ConnectionRefusedError:
    print(f"CLOSED: {host}:{port} refused the connection - network ADB is off.")
    sys.exit(0)
except OSError as e:
    print(f"UNKNOWN: {host}:{port} - {e}. Is the TV on and reachable? (ping {host})")
    sys.exit(2)
