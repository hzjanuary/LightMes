package com.mycompany.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class ChatServer {
    private static final Set<PrintWriter> clientWriters = new HashSet<>();

    public static void main(String[] args) throws Exception {
        System.out.println("[SERVER] Chat Server đang chạy tại port 6666...");
        try (ServerSocket listener = new ServerSocket(6666)) {
            while (true) {
                new ClientHandler(listener.accept()).start();
            }
        }
    }

    private static class ClientHandler extends Thread {
        private final Socket socket; 
        private PrintWriter out;
        private BufferedReader in;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override 
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                
                synchronized (clientWriters) {
                    clientWriters.add(out);
                }

                String message;
                while ((message = in.readLine()) != null) {
                    // Log ngắn gọn trên màn hình Server
                    if (message.startsWith("TEXT:")) {
                        System.out.println("[LOG] " + message.substring(5));
                    } else if (message.startsWith("FILE:")) {
                        String[] parts = message.substring(5).split(":", 3);
                        if (parts.length >= 2) {
                            System.out.println("[LOG] User [" + parts[0] + "] đã gửi file: " + parts[1]);
                        }
                    }

                    // Phát thanh tin nhắn (Text hoặc Base64 File) cho mọi người
                    synchronized (clientWriters) {
                        for (PrintWriter writer : clientWriters) {
                            writer.println(message);
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("[DISCONNECT] Một client đã ngắt kết nối.");
            } finally {
                if (out != null) {
                    synchronized (clientWriters) {
                        clientWriters.remove(out);
                    }
                }
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }
}