package com.mycompany.client; 

import java.awt.*;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;

public class ChatClientGUI {
    
    private final JFrame frame = new JFrame("Chat Client");
    private final JTextPane chatPane = new JTextPane();
    private final JTextField textField = new JTextField(); 
    private final JButton sendButton = new JButton("Send");
    private final JButton emojiButton = new JButton("😊");
    private final JButton attachButton = new JButton("📎");
    private final JLabel statusLabel = new JLabel("🔴 Đang chờ kết nối...");
    
    private BufferedReader in;
    private PrintWriter out;
    private String clientName;
    
    // Lưu trữ khung HTML chat
    private final StringBuilder htmlContent = new StringBuilder();
    
    // Bộ nhớ tạm để lưu các file nhận được (ID File -> Dữ liệu Byte)
    private final HashMap<String, byte[]> fileStorage = new HashMap<>();

    public ChatClientGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // --- 1. HEADER ---
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(86, 130, 163)); 
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        JLabel titleLabel = new JLabel("💬 Global Chat Room");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(Color.WHITE);

        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statusLabel.setForeground(new Color(215, 232, 244)); 

        headerPanel.add(titleLabel, BorderLayout.WEST);
        headerPanel.add(statusLabel, BorderLayout.EAST);

        // --- 2. CHAT AREA ---
        chatPane.setContentType("text/html");
        chatPane.setEditable(false);
        chatPane.setBackground(new Color(230, 235, 239)); 
        
        htmlContent.append("<html><body style='font-family: \"Segoe UI\", Arial, sans-serif; padding: 10px; margin: 0;'>");
        chatPane.setText(htmlContent.toString() + "</body></html>");

        JScrollPane scrollPane = new JScrollPane(chatPane);
        scrollPane.setBorder(null); 

        // Xử lý sự kiện khi click vào link tải File
        chatPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                String fileId = e.getDescription(); // ID của file
                if (fileStorage.containsKey(fileId)) {
                    downloadFile(fileId, fileStorage.get(fileId));
                }
            }
        });

        // --- 3. INPUT AREA ---
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        bottomPanel.setBackground(Color.WHITE);
        bottomPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 220, 220)),
                BorderFactory.createEmptyBorder(10, 15, 10, 15)
        ));

        textField.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        
        // Khung chứa các nút Emoji và Attach
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actionPanel.setBackground(Color.WHITE);
        
        emojiButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
        emojiButton.setContentAreaFilled(false);
        emojiButton.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        emojiButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        attachButton.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
        attachButton.setContentAreaFilled(false);
        attachButton.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 10));
        attachButton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        actionPanel.add(emojiButton);
        actionPanel.add(attachButton);

        sendButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        sendButton.setBackground(new Color(86, 130, 163)); 
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        JPanel inputWrapper = new JPanel(new BorderLayout(5, 0));
        inputWrapper.setBackground(Color.WHITE);
        inputWrapper.add(actionPanel, BorderLayout.WEST);
        inputWrapper.add(textField, BorderLayout.CENTER);
        
        bottomPanel.add(inputWrapper, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);

        // --- RÁP FRAME ---
        frame.getContentPane().add(headerPanel, BorderLayout.NORTH);
        frame.getContentPane().add(scrollPane, BorderLayout.CENTER);
        frame.getContentPane().add(bottomPanel, BorderLayout.SOUTH);

        // --- SỰ KIỆN GỬI TEXT ---
        ActionListener sendListener = e -> {
            String msg = textField.getText();
            if (!msg.trim().isEmpty() && out != null) {
                out.println(clientName + ": " + msg); 
                textField.setText(""); 
            }
        };
        textField.addActionListener(sendListener);
        sendButton.addActionListener(sendListener);

        // --- SỰ KIỆN CHỌN EMOJI ---
        setupEmojiPicker();

        // --- SỰ KIỆN GỬI FILE ---
        attachButton.addActionListener(e -> sendFile());
    }

    // Cài đặt bảng chọn Emoji
    private void setupEmojiPicker() {
        JPopupMenu emojiMenu = new JPopupMenu();
        String[] emojis = {"😀", "😂", "🥰", "😎", "😭", "😡", "👍", "❤️", "🎉", "🔥"};
        JPanel emojiPanel = new JPanel(new GridLayout(2, 5));
        for (String em : emojis) {
            JButton btn = new JButton(em);
            btn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
            btn.setBorderPainted(false);
            btn.setFocusPainted(false);
            btn.addActionListener(e -> {
                textField.setText(textField.getText() + em);
                emojiMenu.setVisible(false);
                textField.requestFocus();
            });
            emojiPanel.add(btn);
        }
        emojiMenu.add(emojiPanel);
        emojiButton.addActionListener(e -> emojiMenu.show(emojiButton, 0, -emojiMenu.getPreferredSize().height));
    }

    // Hàm chọn và mã hóa file để gửi
    private void sendFile() {
        JFileChooser fileChooser = new JFileChooser();
        int result = fileChooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try {
                byte[] fileBytes = Files.readAllBytes(file.toPath());
                // Giới hạn file 5MB để an toàn cho đường truyền String
                if (fileBytes.length > 5 * 1024 * 1024) {
                    JOptionPane.showMessageDialog(frame, "File quá lớn! Vui lòng chọn file dưới 5MB.", "Lỗi", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                // Mã hóa Byte thành chuỗi Base64
                String base64String = Base64.getEncoder().encodeToString(fileBytes);
                
                // Format gửi: [FILE]:ten_file.ext:chuoi_base_64
                out.println(clientName + ": [FILE]:" + file.getName() + ":" + base64String);
                
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame, "Không thể đọc file!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // Hàm lưu file xuống máy khi click vào link
    private void downloadFile(String fileName, byte[] fileData) {
        JFileChooser fileChooser = new JFileChooser();
        // Cắt bỏ phần timestamp ID ở đầu tên file (nếu có)
        String originalName = fileName.contains("_") ? fileName.substring(fileName.indexOf("_") + 1) : fileName;
        fileChooser.setSelectedFile(new File(originalName));
        
        int result = fileChooser.showSaveDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File saveFile = fileChooser.getSelectedFile();
            try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                fos.write(fileData);
                JOptionPane.showMessageDialog(frame, "Đã lưu file thành công:\n" + saveFile.getAbsolutePath(), "Thành công", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame, "Lỗi khi lưu file!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void appendMessage(String sender, String message) {
        SwingUtilities.invokeLater(() -> {
            
            // --- KIỂM TRA NẾU LÀ TIN NHẮN CHỨA FILE ---
            if (message.startsWith("[FILE]:")) {
                String[] parts = message.split(":", 3);
                if (parts.length == 3) {
                    String fileName = parts[1];
                    String base64Data = parts[2];
                    
                    // Lưu file vào bộ nhớ tạm bằng ID duy nhất để bấm vào link có thể tải
                    String fileId = System.currentTimeMillis() + "_" + fileName;
                    try {
                        byte[] fileData = Base64.getDecoder().decode(base64Data);
                        fileStorage.put(fileId, fileData);
                        
                        // Đổi nội dung tin nhắn hiển thị thành một cục UI "File" có link
                        message = "📁 <b>" + fileName + "</b><br>"
                                + "<a href='" + fileId + "' style='color: #0056b3; text-decoration: none;'>⬇ Nhấn vào đây để tải về</a>";
                    } catch (Exception e) {
                        message = "<i>[Lỗi: File bị hỏng trong quá trình truyền tải]</i>";
                    }
                }
            }
            // ------------------------------------------

            if (sender.equalsIgnoreCase("System") || sender.equalsIgnoreCase("Hệ thống")) {
                htmlContent.append("<table width='100%'><tr><td align='center'>")
                           .append("<font face='Segoe UI, Arial' size='3' color='#888888'><i>")
                           .append(message)
                           .append("</i></font></td></tr></table><br>");
            } else if (sender.equals(clientName)) {
                htmlContent.append("<table width='100%'><tr><td align='right'>")
                           .append("<table bgcolor='#DCF8C6' cellpadding='8' cellspacing='0'><tr><td>")
                           .append("<font face='Segoe UI, Arial' size='4' color='black'>")
                           .append(message)
                           .append("</font></td></tr></table></td></tr></table><br>");
            } else {
                htmlContent.append("<table width='100%'><tr><td align='left'>")
                           .append("<table bgcolor='#FFFFFF' cellpadding='8' cellspacing='0'><tr><td>")
                           .append("<font face='Segoe UI, Arial' size='3' color='#5682A3'><b>")
                           .append(sender)
                           .append("</b></font><br>")
                           .append("<font face='Segoe UI, Arial' size='4' color='black'>")
                           .append(message)
                           .append("</font></td></tr></table></td></tr></table><br>");
            }
            
            chatPane.setText(htmlContent.toString() + "</body></html>");
            chatPane.setCaretPosition(chatPane.getDocument().getLength());
        });
    }

    private void connectToServer() {
        clientName = JOptionPane.showInputDialog(
            frame, "Nhập tên hiển thị của bạn:", "Đăng nhập Chat", JOptionPane.PLAIN_MESSAGE
        );
        if (clientName == null || clientName.trim().isEmpty()) {
            clientName = "Guest_" + (int)(Math.random() * 1000);
        }
        frame.setTitle("Telegram Lite - " + clientName);

        new Thread(() -> {
            try {
                // Nhớ đổi lại IP này thành IP/localhost thực tế của Server bạn đang chạy nhé
                Socket socket = new Socket("127.0.0.1", 6666); 
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                SwingUtilities.invokeLater(() -> statusLabel.setText("🟢 Online: " + clientName));
                out.println("System: " + clientName + " đã tham gia phòng chat!");

                String line;
                while ((line = in.readLine()) != null) {
                    String[] parts = line.split(": ", 2);
                    if (parts.length == 2) {
                        appendMessage(parts[0], parts[1]);
                    } else {
                        appendMessage("System", line);
                    }
                }
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("🔴 Mất kết nối"));
                appendMessage("System", "Mất kết nối tới máy chủ.");
            }
        }).start();
    }

    public static void main(String[] args) {
        ChatClientGUI client = new ChatClientGUI();
        client.frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        client.frame.setSize(480, 680); 
        client.frame.setLocationRelativeTo(null); 
        client.frame.setVisible(true);
        
        client.connectToServer();
    }
}