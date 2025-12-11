# Proxy

TCP-UDP Proxy Network for Data Aggregation

## Quick Start

### Compile
```bash
javac -source 8 -target 8 *.java
```

### Run Example
```bash
# Terminal 1: Start TCP server
java TCPServer -port 8001 -key temperature -value 25

# Terminal 2: Start UDP server
java UDPServer -port 8002 -key humidity -value 60

# Terminal 3: Start Proxy
java Proxy -port 9000 -server localhost 8001 -server localhost 8002

# Terminal 4: Test with client
java TCPClient -address localhost -port 9000 -command GET NAMES
```

## Documentation

See `Documentation.md` for complete implementation details, protocol specification, and usage examples.

## Features

- ✅ Full TCP and UDP support
- ✅ Protocol translation (TCP ↔ UDP)
- ✅ Proxy-to-proxy communication
- ✅ Arbitrary network topologies
- ✅ Thread-safe concurrent operations

