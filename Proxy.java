import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Proxy {
    private int port;
    private List<ServerInfo> servers = new ArrayList<>();
    private Map<String, ServerInfo> keyToServer = new ConcurrentHashMap<>();
    private Set<String> allKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    static class ServerInfo {
        String address;
        int port;
        boolean isTCP;
        boolean isProxy;
        Set<String> keys = new HashSet<>();

        ServerInfo(String address, int port) {
            this.address = address;
            this.port = port;
        }
    }

    public static void main(String[] args) {
        int port = 0;
        List<ServerInfo> servers = new ArrayList<>();

        try {
            for (int i = 0; i < args.length; ) {
                switch (args[i]) {
                    case "-port":
                        port = Integer.parseInt(args[i + 1]);
                        i += 2;
                        break;
                    case "-server":
                        String address = args[i + 1];
                        int serverPort = Integer.parseInt(args[i + 2]);
                        servers.add(new ServerInfo(address, serverPort));
                        i += 3;
                        break;
                    default:
                        System.err.println("Unknown parameter: " + args[i]);
                        System.exit(1);
                }
            }

            if (port == 0 || servers.isEmpty()) {
                System.err.println("Incorrect execution syntax");
                System.exit(1);
            }

            Proxy proxy = new Proxy(port, servers);
            proxy.start();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    public Proxy(int port, List<ServerInfo> servers) {
        this.port = port;
        this.servers = servers;
    }

    public void start() {
        // Detect protocol and discover keys from all servers
        discoverServers();

        // Start TCP listener thread
        Thread tcpThread = new Thread(this::startTCPListener);
        tcpThread.start();

        // Start UDP listener thread
        Thread udpThread = new Thread(this::startUDPListener);
        udpThread.start();

        try {
            tcpThread.join();
            udpThread.join();
        } catch (InterruptedException e) {
            System.err.println("Interrupted: " + e.getMessage());
        }
    }

    private void discoverServers() {
        for (ServerInfo server : servers) {
            // Try TCP first
            boolean isTCP = tryTCP(server);
            if (isTCP) {
                server.isTCP = true;
                System.out.println("Server " + server.address + ":" + server.port + " is TCP");
                // Get keys from this server
                discoverKeys(server);
            } else {
                // Try UDP
                server.isTCP = false;
                if (tryUDP(server)) {
                    System.out.println("Server " + server.address + ":" + server.port + " is UDP");
                    // Get keys from this server
                    discoverKeys(server);
                } else {
                    System.err.println("Could not connect to " + server.address + ":" + server.port);
                }
            }
        }
    }

    private boolean tryTCP(ServerInfo server) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(server.address, server.port), 2000);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            
            out.println("GET NAMES");
            String response = in.readLine();
            
            in.close();
            out.close();
            socket.close();
            
            return response != null && response.startsWith("OK");
        } catch (IOException e) {
            return false;
        }
    }

    private boolean tryUDP(ServerInfo server) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(2000);
            String command = "GET NAMES\n";
            byte[] buffer = command.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length,
                    InetAddress.getByName(server.address), server.port);
            socket.send(packet);

            buffer = new byte[1024];
            packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            String response = new String(packet.getData(), 0, packet.getLength()).trim();
            socket.close();
            return response.startsWith("OK");
        } catch (Exception e) {
            return false;
        }
    }

    private void discoverKeys(ServerInfo server) {
        String response = sendCommand(server, "GET NAMES");
        if (response != null && response.startsWith("OK")) {
            String[] parts = response.split(" ");
            if (parts.length >= 3) {
                int count = Integer.parseInt(parts[1]);
                for (int i = 0; i < count && i + 2 < parts.length; i++) {
                    String key = parts[i + 2];
                    server.keys.add(key);
                    allKeys.add(key);
                    keyToServer.put(key, server);
                }
                // Check if it's a proxy (returns multiple keys or special response)
                server.isProxy = count > 1 || response.contains("PROXY");
            }
        }
    }

    private String sendCommand(ServerInfo server, String command) {
        if (server.isTCP) {
            return sendTCPCommand(server, command);
        } else {
            return sendUDPCommand(server, command);
        }
    }

    private String sendTCPCommand(ServerInfo server, String command) {
        try {
            Socket socket = new Socket(server.address, server.port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println(command);
            if (command.equals("QUIT")) {
                socket.close();
                return null;
            }
            String response = in.readLine();

            in.close();
            out.close();
            socket.close();
            return response;
        } catch (IOException e) {
            System.err.println("TCP error: " + e.getMessage());
            return null;
        }
    }

    private String sendUDPCommand(ServerInfo server, String command) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(2000);

            byte[] buffer = (command + "\n").getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length,
                    InetAddress.getByName(server.address), server.port);
            socket.send(packet);

            if (command.equals("QUIT")) {
                socket.close();
                return null;
            }

            buffer = new byte[1024];
            packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            String response = new String(packet.getData(), 0, packet.getLength()).trim();

            socket.close();
            return response;
        } catch (IOException e) {
            System.err.println("UDP error: " + e.getMessage());
            return null;
        }
    }

    private void startTCPListener() {
        try {
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println("TCP listener started on port " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleTCPClient(clientSocket)).start();
                } catch (IOException e) {
                    if (running) {
                        System.err.println("TCP accept error: " + e.getMessage());
                    }
                }
            }
            serverSocket.close();
        } catch (IOException e) {
            System.err.println("TCP listener error: " + e.getMessage());
        }
    }

    private void handleTCPClient(Socket clientSocket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);

            String request = in.readLine();
            String response = processCommand(request);

            out.println(response);

            in.close();
            out.close();
            clientSocket.close();
        } catch (IOException e) {
            System.err.println("TCP client handler error: " + e.getMessage());
        }
    }

    private void startUDPListener() {
        try {
            DatagramSocket socket = new DatagramSocket(port);
            System.out.println("UDP listener started on port " + port);

            while (running) {
                try {
                    byte[] buffer = new byte[1024];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    final InetAddress clientAddress = packet.getAddress();
                    final int clientPort = packet.getPort();
                    final String request = new String(packet.getData(), 0, packet.getLength()).trim();

                    new Thread(() -> {
                        String response = processCommand(request);
                        try {
                            byte[] responseData = response.getBytes();
                            DatagramPacket responsePacket = new DatagramPacket(responseData,
                                    responseData.length, clientAddress, clientPort);
                            socket.send(responsePacket);
                        } catch (IOException e) {
                            System.err.println("UDP response error: " + e.getMessage());
                        }
                    }).start();
                } catch (IOException e) {
                    if (running) {
                        System.err.println("UDP receive error: " + e.getMessage());
                    }
                }
            }
            socket.close();
        } catch (SocketException e) {
            System.err.println("UDP listener error: " + e.getMessage());
        }
    }

    private String processCommand(String request) {
        if (request == null || request.trim().isEmpty()) {
            return "NA";
        }

        String[] parts = request.trim().split("\\s+");
        String command = parts[0];

        switch (command) {
            case "GET":
                if (parts.length < 2) {
                    return "NA";
                }
                String getType = parts[1];
                if (getType.equals("NAMES")) {
                    return handleGetNames();
                } else if (getType.equals("VALUE")) {
                    if (parts.length < 3) {
                        return "NA";
                    }
                    return handleGetValue(parts[2]);
                }
                return "NA";

            case "SET":
                if (parts.length < 3) {
                    return "NA";
                }
                String keyName = parts[1];
                try {
                    int value = Integer.parseInt(parts[2]);
                    return handleSet(keyName, value);
                } catch (NumberFormatException e) {
                    return "NA";
                }

            case "QUIT":
                handleQuit();
                return null;

            default:
                return "NA";
        }
    }

    private String handleGetNames() {
        if (allKeys.isEmpty()) {
            return "OK 0";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("OK ").append(allKeys.size());
        for (String key : allKeys) {
            sb.append(" ").append(key);
        }
        return sb.toString();
    }

    private String handleGetValue(String keyName) {
        ServerInfo server = keyToServer.get(keyName);
        if (server == null) {
            return "NA";
        }
        return sendCommand(server, "GET VALUE " + keyName);
    }

    private String handleSet(String keyName, int value) {
        ServerInfo server = keyToServer.get(keyName);
        if (server == null) {
            return "NA";
        }
        return sendCommand(server, "SET " + keyName + " " + value);
    }

    private void handleQuit() {
        running = false;
        for (ServerInfo server : servers) {
            sendCommand(server, "QUIT");
        }
        System.out.println("Terminating");
        System.exit(0);
    }
}
