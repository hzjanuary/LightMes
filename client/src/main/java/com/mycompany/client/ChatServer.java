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
        System.out.println("[SERVER] Chat Server is running on port 6666...");
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
                // ÉP CHUẨN UTF-8 ĐỂ KHÔNG BỊ LỖI FONT EMOJI
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                
                // Add new client to the broadcast list
                synchronized (clientWriters) {
                    clientWriters.add(out);
                }

                String message;
                // Receive message from a Client
                while ((message = in.readLine()) != null) {
                    
                    String[] parts = message.split(": ", 2); 
                    
                    if (parts.length == 2) {
                        String userName = parts[0]; 
                        String content = parts[1];  
                        
                        // --- KIỂM TRA ĐỂ KHÔNG IN CHUỖI BASE64 CỦA FILE RA CONSOLE ---
                        if (content.startsWith("[FILE]:")) {
                            // Cắt lấy tên file để log cho gọn
                            String[] fileParts = content.split(":", 3);
                            String fileName = (fileParts.length >= 2) ? fileParts[1] : "unknown_file";
                            System.out.println("[RECEIVE] User [" + userName + "] sent a file: " + fileName);
                        } else {
                            // Nếu là tin nhắn text thường thì in ra bình thường
                            System.out.println("[RECEIVE] User [" + userName + "] sent: " + content);
                        }
                        // -------------------------------------------------------------
                        
                    } else {
                        System.out.println("[RECEIVE] " + message);
                    }

                    // Broadcast the original 'message' to ALL other Clients
                    synchronized (clientWriters) {
                        for (PrintWriter writer : clientWriters) {
                            writer.println(message);
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("[DISCONNECT] A client disconnected due to a connection error.");
            } finally {
                // When client exits, remove from the list
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