package com.mycompany.client;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ChatServer {

    // Thread-safe set of all connected client handlers
    private static final ConcurrentHashMap<ClientHandler, Boolean> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        int port = 6666;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { System.err.println("[WARN] Port không hợp lệ, dùng mặc định 6666"); }
        }

        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║      LightMes Chat Server v2.0       ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("[INFO] Port: " + port);

        // Print all non-loopback IPv4 addresses so users know which IP to use
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        System.out.println("[INFO] LAN IP: " + addr.getHostAddress()
                                + "  ← Client dùng IP này để kết nối");
                    }
                }
            }
        } catch (SocketException ignored) {}

        System.out.println("[INFO] Đang lắng nghe kết nối...\n");

        try (ServerSocket server = new ServerSocket(port)) {
            server.setReuseAddress(true);
            while (true) {
                Socket socket = server.accept();
                ClientHandler handler = new ClientHandler(socket);
                handler.start();
                System.out.println(ts() + " [+] Kết nối mới: "
                        + socket.getRemoteSocketAddress());
            }
        } catch (IOException e) {
            System.err.println("[FATAL] Server lỗi: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Broadcast a message to ALL connected clients (non-blocking). */
    static void broadcast(String message) {
        for (ClientHandler c : clients.keySet()) {
            c.enqueue(message);
        }
    }

    /** Send a message to a specific user by displayName. Returns true if user found. */
    static boolean sendTo(String targetUser, String message) {
        for (ClientHandler c : clients.keySet()) {
            if (c.displayName.equals(targetUser)) {
                c.enqueue(message);
                return true;
            }
        }
        return false;
    }

    /** Broadcast updated online user list to all clients. Format: USERLIST:user1,user2,... */
    static void broadcastUserList() {
        String userList = clients.keySet().stream()
                .map(c -> c.displayName)
                .sorted()
                .collect(Collectors.joining(","));
        broadcast("USERLIST:" + userList);
    }

    static void removeClient(ClientHandler h) {
        if (clients.remove(h) != null) {
            System.out.println(ts() + " [-] Ngắt kết nối: " + h.displayName
                    + "  |  Còn lại: " + clients.size());
            // Notify remaining clients
            broadcast("TEXT:System: " + h.displayName + " đã rời phòng chat.");
            broadcastUserList();
        }
    }

    private static String ts() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    // -------------------------------------------------------------------------

    static class ClientHandler extends Thread {
        private final Socket socket;
        private final LinkedBlockingQueue<String> outQueue = new LinkedBlockingQueue<>();
        private volatile boolean alive = true;
        volatile String displayName = "unknown";
        private static final UserManager userManager = new UserManager();

        ClientHandler(Socket socket) {
            this.socket = socket;
            setDaemon(true);
        }

        void enqueue(String msg) {
            if (alive) outQueue.offer(msg);
        }

        @Override
        public void run() {
            Thread writerThread = null;
            
            try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            ) {
                // 1. Xác thực — ghi phản hồi ĐỒNG BỘ để tránh race condition
                BufferedWriter authWriter = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                
                String authLine = reader.readLine();
                boolean authed = false;
                
                if (authLine != null && authLine.startsWith("AUTH:")) {
                    String[] parts = authLine.split(":", 4);
                    if (parts.length == 4) {
                        String mode = parts[1];
                        String user = parts[2];
                        String pass = parts[3];
                        if (mode.equals("LOGIN")) {
                            authed = userManager.login(user, pass);
                            System.out.println(ts() + " [AUTH] LOGIN '" + user + "' => " + (authed ? "OK" : "FAIL"));
                        } else if (mode.equals("REGISTER")) {
                            authed = userManager.register(user, pass);
                            System.out.println(ts() + " [AUTH] REGISTER '" + user + "' => " + (authed ? "OK" : "FAIL"));
                        }
                    } else {
                        System.err.println(ts() + " [AUTH] Malformed AUTH line: " + authLine);
                    }
                } else {
                    System.err.println(ts() + " [AUTH] No AUTH line or wrong format: " + authLine);
                }
                
                // Ghi phản hồi xác thực trực tiếp (đồng bộ, không qua queue)
                if (authed) {
                    authWriter.write("AUTH:OK");
                    authWriter.newLine();
                    authWriter.flush();
                    
                    displayName = authLine.split(":", 4)[2];
                    clients.put(this, Boolean.TRUE);
                    System.out.println(ts() + " [INFO] " + displayName + " đã gia nhập chat. Tổng: " + clients.size());
                    
                    // Chỉ khởi động writer thread SAU KHI xác thực thành công
                    writerThread = startWriterThread();

                    // Broadcast user list to all clients
                    broadcastUserList();
                } else {
                    authWriter.write("AUTH:FAIL");
                    authWriter.newLine();
                    authWriter.flush();
                    socket.close();
                    return;
                }
                
                // 2. Sau khi xác thực thành công, vào chat
                String line;
                while ((line = reader.readLine()) != null) {
                    logIncoming(line);

                    if (line.startsWith("PRIVATE:")) {
                        // Format: PRIVATE:sender:target:message
                        handlePrivateMessage(line);
                    } else if (line.startsWith("LOGOUT:")) {
                        // Client requested logout
                        System.out.println(ts() + " [LOGOUT] " + displayName);
                        break; // Exit read loop → finally will clean up
                    } else {
                        // Public messages (TEXT:, FILE:) → broadcast to all
                        broadcast(line);
                    }
                }
            } catch (IOException e) {
                if (alive) System.out.println(ts() + " [READ-ERR] " + displayName + ": " + e.getMessage());
            } finally {
                alive = false;
                if (writerThread != null) writerThread.interrupt();
                removeClient(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private void handlePrivateMessage(String line) {
            // PRIVATE:sender:target:message
            String payload = line.substring(8); // after "PRIVATE:"
            int s1 = payload.indexOf(':');
            if (s1 < 0) return;
            int s2 = payload.indexOf(':', s1 + 1);
            if (s2 < 0) return;

            String sender  = payload.substring(0, s1);
            String target  = payload.substring(s1 + 1, s2);
            String message = payload.substring(s2 + 1);

            System.out.println(ts() + " [PRIV] " + sender + " → " + target + ": " + message);

            // Send to target
            if (!sendTo(target, line)) {
                // Target not online — notify sender
                sendTo(sender, "TEXT:System: Người dùng '" + target + "' không trực tuyến.");
            }
            // Also echo back to sender so they see their own message
            sendTo(sender, line);
        }

        private Thread startWriterThread() {
            Thread t = new Thread(() -> {
                try (BufferedWriter bw = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    while (alive) {
                        try {
                            String msg = outQueue.poll(2, TimeUnit.SECONDS);
                            if (msg == null) continue;
                            bw.write(msg);
                            bw.newLine();
                            // Drain any additional queued messages before flushing (efficiency)
                            String next;
                            while ((next = outQueue.poll()) != null) {
                                bw.write(next);
                                bw.newLine();
                            }
                            bw.flush();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (IOException e) {
                            System.out.println(ts() + " [WRITE-ERR] " + displayName + ": " + e.getMessage());
                            alive = false;
                            break;
                        }
                    }
                } catch (IOException e) {
                    alive = false;
                }
            }, "writer@" + socket.getRemoteSocketAddress());
            t.setDaemon(true);
            t.start();
            return t;
        }

        private void logIncoming(String msg) {
            if (msg.startsWith("TEXT:")) {
                String body = msg.substring(5);
                int sep = body.indexOf(": ");
                if (sep > 0) displayName = body.substring(0, sep);
                System.out.println(ts() + " [MSG ] " + body);
            } else if (msg.startsWith("FILE:")) {
                String[] parts = msg.substring(5).split(":", 3);
                if (parts.length >= 2) {
                    displayName = parts[0];
                    long sizeKb = parts.length > 2 ? (long) (parts[2].length() * 0.75 / 1024) : 0;
                    System.out.printf("%s [FILE] %s → %s  (%d KB)%n", ts(), parts[0], parts[1], sizeKb);
                }
            } else if (msg.startsWith("PRIVATE:")) {
                // Logged in handlePrivateMessage
            } else if (msg.startsWith("LOGOUT:")) {
                System.out.println(ts() + " [LOGOUT] " + displayName);
            }
        }
    }
}