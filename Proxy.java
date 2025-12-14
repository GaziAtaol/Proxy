import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
//=====================================================================================================
public class Proxy {
    private int port;
    //input
    private List<ServerInfo> servers = new ArrayList<>();
    // Her key için hangi node'a gideceğimizi tutuyoruz
    private Map<String, ServerInfo> keyToServer = new ConcurrentHashMap<>();
    // Tüm bilinen key isimleri
    private Set<String> allKeys = ConcurrentHashMap.newKeySet();
    // PROXYNAMES "session"larını takip ederek cycle'ları engelliyoruz
    private Set<String> processedSessions = ConcurrentHashMap.newKeySet();

    private volatile boolean running = true;
//=====================================================================================================
    static class ServerInfo {
        String address;
        int port;
        boolean isTCP;
        boolean isProxy;
        Set<String> keys = ConcurrentHashMap.newKeySet();

        ServerInfo(String address, int port) {
            this.address = address;
            this.port = port;
        }

        @Override
        public String toString() {
            return address + ":" + port + (isTCP ? "(TCP)" : "(UDP)") + (isProxy ? "[proxy]" : "[server]");
        }
    }
//=====================================================================================================
    public static void main(String[] args) {
        int port = 0;
        List<ServerInfo> servers = new ArrayList<>();
        try {
            for (int i = 0; i < args.length;) {
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
        }
        catch (Exception e){System.err.println("Error: " + e.getMessage());System.exit(1);}
    }
//=====================================================================================================
    public Proxy(int port, List<ServerInfo> servers) {
        this.port = port;
        this.servers = servers;
    }
    public void start() {
        // 1) Hangi node TCP / UDP, önce onu bul
        discoverServers();

        // 2) Listener'ları aç
        Thread tcpThread = new Thread(this::startTCPListener);
        Thread udpThread = new Thread(this::startUDPListener);

        tcpThread.start();
        udpThread.start();

        try {
            tcpThread.join();
            udpThread.join();
        } catch (InterruptedException e) {
            System.err.println("Interrupted: " + e.getMessage());
        }
    }
    // ---------------------------------------------------
    // DISCOVERY KISMI
    // ---------------------------------------------------
    private void discoverServers() {
        for (ServerInfo server : servers) {
            // Önce TCP dene
            boolean isTCP = tryTCP(server);
            if (isTCP) {
                server.isTCP = true;
                System.out.println("Server " + server + " is TCP");
            } else {
                // Olmazsa UDP
                server.isTCP = false;
                if (tryUDP(server)) {
                    System.out.println("Server " + server + " is UDP");
                } else {
                    System.err.println("Could not connect to " + server.address + ":" + server.port);
                }
            }
        }
        // İlk routing tablosu için bir kez global discovery yap
        String initialSession = UUID.randomUUID().toString();
        System.out.println("Initial discovery session: " + initialSession);
        String response = gatherKeysForSession(initialSession);
        System.out.println("Initial keys: " + response);
    }
    //=====================================================================================================
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
        }
        catch (IOException e) {return false;}
    }
    //=====================================================================================================
    private boolean tryUDP(ServerInfo server) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(2000);
            String command = "GET NAMES\n";
            byte[] buffer = command.getBytes();

            DatagramPacket packet = new DatagramPacket(
                    buffer,
                    buffer.length,
                    InetAddress.getByName(server.address),
                    server.port
            );

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
    // ---------------------------------------------------
    // PROXY → NODE KOMUT GÖNDERME
    // ---------------------------------------------------
    private String sendCommand(ServerInfo server, String command) {
        // İlk önce belirlenen protokolü dene
        String response = null;
        if (server.isTCP) {
            response = sendTCPCommand(server, command);
            // TCP başarısız olduysa, UDP'yi dene (fallback)
            if (response == null && !command.startsWith("QUIT")) {
                System.err.println("TCP failed for " + server + ", trying UDP fallback");
                response = sendUDPCommand(server, command);
                // UDP başarılı olduysa, bundan sonra bu sunucu için UDP kullan
                if (response != null) {
                    server.isTCP = false;
                }
            }
        } else {
            response = sendUDPCommand(server, command);
            // UDP başarısız olduysa, TCP'yi dene (fallback)
            if (response == null && !command.startsWith("QUIT")) {
                System.err.println("UDP failed for " + server + ", trying TCP fallback");
                response = sendTCPCommand(server, command);
                // TCP başarılı olduysa, bundan sonra bu sunucu için TCP kullan
                if (response != null) {
                    server.isTCP = true;
                }
            }
        }
        return response;
    }

    private String sendTCPCommand(ServerInfo server, String command) {
        try {
            Socket socket = new Socket(server.address, server.port);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            out.println(command);
            if (command.startsWith("QUIT")) {
                socket.close();
                return null;
            }
            String response = in.readLine();

            in.close();
            out.close();
            socket.close();
            return response;
        }
        catch (IOException e){System.err.println("TCP error with " + server + ": " + e.getMessage());return null;}
    }

    private String sendUDPCommand(ServerInfo server, String command) {
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(2000);

            byte[] buffer = (command + "\n").getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length,
                    InetAddress.getByName(server.address), server.port);
            socket.send(packet);

            if (command.startsWith("QUIT"))
            {
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
            System.err.println("UDP error with " + server + ": " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------
    // DINLEYICILER (TCP / UDP)
    // ---------------------------------------------------

    private void startTCPListener() {
        try {
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println("TCP listener started on port " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleTCPClient(clientSocket)).start();
                }
                catch (IOException e) {
                    if (running) {
                        System.err.println("TCP accept error: " + e.getMessage());
                    }
                }
            }
            serverSocket.close();
        }
        catch (IOException e) {System.err.println("TCP listener error: " + e.getMessage());}
    }
    //=====================================================================================================
    private void handleTCPClient(Socket clientSocket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
            String request = in.readLine();
            String response = processCommand(request);

            // QUIT durumunda response null olabilir
            if (response != null) out.println(response);

            in.close();
            out.close();
            clientSocket.close();
        } catch (IOException e) {System.err.println("TCP client handler error: " + e.getMessage());}
    }
    //=====================================================================================================
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
                        if (response == null) {
                            // QUIT vs. için cevap yok
                            return;
                        }

                        try {
                            byte[] responseData = response.getBytes();
                            DatagramPacket responsePacket = new DatagramPacket(responseData, responseData.length, clientAddress, clientPort);
                            synchronized (socket) {socket.send(responsePacket);}
                        }
                        catch (IOException e) {System.err.println("UDP response error: " + e.getMessage());}
                    }).start();
                }
                catch (IOException e) {
                    if (running) System.err.println("UDP receive error: " + e.getMessage());
                }
            }
            socket.close();
        } catch (SocketException e) {System.err.println("UDP listener error: " + e.getMessage());}
    }

    // ---------------------------------------------------
    // KOMUT ISLEME KISMI
    // ---------------------------------------------------
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

            case "PROXYNAMES":
                if (parts.length < 2) {
                    return "NA";
                }
                return handleProxyNames(parts[1]);

            case "QUIT":
                handleQuit();
                return null;

            default:
                return "NA";
        }
    }

    // ---------------------------------------------------
    // GET NAMES / PROXYNAMES → Çok seviyeli discovery
    // ---------------------------------------------------
    // Normal client'tan gelen GET NAMES
    private String handleGetNames() {
        // Her GET NAMES çağrısında yeni bir discovery "session"
        String sessionId = UUID.randomUUID().toString();
        return gatherKeysForSession(sessionId);
    }

    // Başka bir proxy'den gelen PROXYNAMES <sessionId>
    private String handleProxyNames(String sessionId) {
        return gatherKeysForSession(sessionId);
    }

    /**
     * Belirli bir "session" için, bütün aşağıdaki node'lardan key listelerini toplar.
     * Cycle engellemek için sessionId'yi processedSessions set'inde takip ediyoruz.
     */
    private String gatherKeysForSession(String sessionId) {
        // Aynı session ikinci kez geliyorsa (cycle) → boş liste
        if (!processedSessions.add(sessionId)) {
            return "OK 0";
        }

        Set<String> sessionKeys = new HashSet<>();

        for (ServerInfo server : servers) {
            String response = sendProxyNamesOrGetNames(server, sessionId);
            if (response == null || !response.startsWith("OK")) {
                continue;
            }

            String[] parts = response.split("\\s+");
            if (parts.length < 2) {
                continue;
            }

            int count;
            try {
                count = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }

            for (int i = 0; i < count && (i + 2) < parts.length; i++) {
                String key = parts[i + 2];
                sessionKeys.add(key);
                allKeys.add(key);
                server.keys.add(key);
                // Bu proxy açısından, bu key'e ulaşmak için bu server'a gitmek yeterli
                keyToServer.put(key, server);
            }
        }

        // Session bitti, tekrar gelebilmesi için kaldırıyoruz
        processedSessions.remove(sessionId);

        StringBuilder sb = new StringBuilder();
        sb.append("OK ").append(sessionKeys.size());
        for (String key : sessionKeys) {
            sb.append(" ").append(key);
        }
        return sb.toString();
    }

    /**
     * Önce node'u proxy gibi kullanmayı dener:
     *  PROXYNAMES <sessionId>
     * Eğer "NA" dönerse düz server kabul edip GET NAMES atar.
     */
    private String sendProxyNamesOrGetNames(ServerInfo server, String sessionId) {
        // Önce PROXYNAMES deneyelim (sadece proxy'ler anlayacak)
        String response = sendCommand(server, "PROXYNAMES " + sessionId);
        if (response == null || response.equals("NA") || !response.startsWith("OK")) {
            // Bu node muhtemelen sadece basit server, o zaman normal protokole düş:
            response = sendCommand(server, "GET NAMES");
            server.isProxy = false;
        } else {
            server.isProxy = true;
        }
        return response;
    }

    // ---------------------------------------------------
    // GET VALUE / SET → Dinamik routing
    // ---------------------------------------------------
    private String handleGetValue(String keyName) {
        ServerInfo server = keyToServer.get(keyName);
        if (server == null) {
            // Bu key'i daha önce görmediysek, tüm ağı yeniden tarayalım
            String sessionId = UUID.randomUUID().toString();
            gatherKeysForSession(sessionId);
            server = keyToServer.get(keyName);
            if (server == null) {
                return "NA";
            }
        }
        String response = sendCommand(server, "GET VALUE " + keyName);
        // sendCommand null döndürürse (sunucuya ulaşılamazsa), NA döndür
        return response != null ? response : "NA";
    }

    private String handleSet(String keyName, int value) {
        ServerInfo server = keyToServer.get(keyName);
        if (server == null) {
            // Key yeni bir yerde olabilir, keşfi tazele
            String sessionId = UUID.randomUUID().toString();
            gatherKeysForSession(sessionId);
            server = keyToServer.get(keyName);
            if (server == null) {
                return "NA";
            }
        }
        String response = sendCommand(server, "SET " + keyName + " " + value);
        // sendCommand null döndürürse (sunucuya ulaşılamazsa), NA döndür
        return response != null ? response : "NA";
    }

    // ---------------------------------------------------
    // QUIT
    // ---------------------------------------------------
    private void handleQuit() {
        running = false;
        for (ServerInfo server : servers) {
            sendCommand(server, "QUIT");
        }
        System.out.println("Terminating");
        System.exit(0);
    }
}
