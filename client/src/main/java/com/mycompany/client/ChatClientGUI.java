package com.mycompany.client;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

public class ChatClientGUI {

    // Color palette
    private static final Color CLR_HEADER     = new Color(28, 86, 158);
    private static final Color CLR_BG         = new Color(235, 238, 243);
    private static final Color CLR_DIVIDER    = new Color(208, 213, 224);
    private static final Color CLR_ACCENT     = new Color(28, 86, 158);
    private static final Color CLR_STATUS_ON  = new Color(134, 239, 172);
    private static final Color CLR_STATUS_OFF = new Color(252, 165, 165);
    private static final Color CLR_SIDEBAR_BG = new Color(34, 45, 65);
    private static final Color CLR_SIDEBAR_HOVER = new Color(44, 62, 90);
    private static final Color CLR_SIDEBAR_ACTIVE = new Color(28, 86, 158);
    private static final Color CLR_UNREAD     = new Color(255, 87, 87);

    private static final DateTimeFormatter TIME_FMT  = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024; // 10 MB

    // A special target name meaning "all" / public chat
    private static final String TARGET_ALL = "__ALL__";

    // Swing components
    private final JFrame     frame        = new JFrame("LightMes");
    private final JTextPane  chatPane     = new JTextPane();
    private final JTextField textField    = new JTextField();
    private final JButton    sendButton   = new JButton("Send >");
    private final JButton    emojiButton  = new JButton("Emoji");
    private final JButton    attachButton = new JButton("File");
    private final JLabel     statusLabel  = new JLabel("  Connecting...");
    private final JLabel     titleLabel   = new JLabel("LightMes Chat");

    // Sidebar
    private final DefaultListModel<String> userListModel = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(userListModel);
    private final JButton allChatButton = new JButton("All Chat");
    private final JButton logoutButton  = new JButton("Logout");
    private final JButton refreshButton = new JButton("Refresh");
    private final JLabel chatTargetLabel = new JLabel("# All");

    // State
    private PrintWriter out;
    private Socket currentSocket;
    private volatile boolean connected = false;
    private String clientName = "Guest";
    private String currentTarget = TARGET_ALL;  // who we're chatting with

    private final ConcurrentHashMap<String, byte[]> fileStorage = new ConcurrentHashMap<>();

    // Per-conversation chat history (target -> HTML string)
    private final Map<String, StringBuilder> chatHistories = new HashMap<>();

    // Unread indicators per conversation
    private final Map<String, Boolean> unreadMap = new HashMap<>();

    public ChatClientGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        buildUI();
    }

    private void buildUI() {
        // ===== HEADER =====
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(CLR_HEADER);
        header.setBorder(BorderFactory.createEmptyBorder(11, 16, 11, 16));

        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 17));
        titleLabel.setForeground(Color.WHITE);

        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLabel.setForeground(new Color(170, 205, 255));

        // Chat target indicator (shows who you're chatting with)
        chatTargetLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        chatTargetLabel.setForeground(new Color(200, 220, 255));
        chatTargetLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel headerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        headerLeft.setOpaque(false);
        headerLeft.add(titleLabel);
        headerLeft.add(chatTargetLabel);

        header.add(headerLeft, BorderLayout.WEST);
        header.add(statusLabel, BorderLayout.EAST);

        // ===== CHAT PANE =====
        chatPane.setContentType("text/html");
        chatPane.setEditable(false);
        chatPane.setBackground(CLR_BG);
        chatPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        chatPane.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
        resetChat();

        JScrollPane scroll = new JScrollPane(chatPane);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        chatPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                byte[] data = fileStorage.get(e.getDescription());
                if (data != null) downloadFile(e.getDescription(), data);
            }
        });

        // ===== SIDEBAR =====
        JPanel sidebar = buildSidebar();

        // ===== BOTTOM INPUT =====
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBackground(Color.WHITE);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, CLR_DIVIDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));

        textField.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
        textField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CLR_DIVIDER, 1, true),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));

        styleIconBtn(emojiButton,  new Font("Segoe UI Emoji", Font.PLAIN, 16));
        styleIconBtn(attachButton, new Font("Segoe UI Emoji", Font.PLAIN, 16));

        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        leftBtns.setBackground(Color.WHITE);
        leftBtns.add(emojiButton);
        leftBtns.add(attachButton);

        sendButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        sendButton.setBackground(CLR_ACCENT);
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setBorderPainted(false);
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));

        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.setBackground(Color.WHITE);
        inputRow.add(leftBtns,  BorderLayout.WEST);
        inputRow.add(textField, BorderLayout.CENTER);

        bottom.add(inputRow,   BorderLayout.CENTER);
        bottom.add(sendButton, BorderLayout.EAST);

        // ===== FRAME LAYOUT =====
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(scroll, BorderLayout.CENTER);

        frame.setLayout(new BorderLayout());
        frame.add(header,      BorderLayout.NORTH);
        frame.add(sidebar,     BorderLayout.WEST);
        frame.add(centerPanel, BorderLayout.CENTER);
        frame.add(bottom,      BorderLayout.SOUTH);

        ActionListener sendAction = e -> sendText();
        textField.addActionListener(sendAction);
        sendButton.addActionListener(sendAction);
        attachButton.addActionListener(e -> sendFile());
        setupEmojiPicker();
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(165, 0));
        sidebar.setBackground(CLR_SIDEBAR_BG);

        // --- All Chat button ---
        allChatButton.setFont(new Font("Segoe UI Emoji", Font.BOLD, 13));
        allChatButton.setForeground(Color.WHITE);
        allChatButton.setBackground(CLR_SIDEBAR_ACTIVE);
        allChatButton.setBorderPainted(false);
        allChatButton.setFocusPainted(false);
        allChatButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        allChatButton.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        allChatButton.setHorizontalAlignment(SwingConstants.LEFT);
        allChatButton.addActionListener(e -> switchTarget(TARGET_ALL));

        // --- Refresh button ---
        refreshButton.setFont(new Font("Segoe UI", Font.BOLD, 12));
        refreshButton.setForeground(Color.WHITE);
        refreshButton.setBackground(new Color(56, 142, 60));
        refreshButton.setBorderPainted(false);
        refreshButton.setFocusPainted(false);
        refreshButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        refreshButton.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        refreshButton.setHorizontalAlignment(SwingConstants.LEFT);
        refreshButton.addActionListener(e -> requestRefreshUsers());

        JPanel topSection = new JPanel();
        topSection.setLayout(new BoxLayout(topSection, BoxLayout.Y_AXIS));
        topSection.setBackground(CLR_SIDEBAR_BG);

        // Make buttons fill the width
        allChatButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, allChatButton.getPreferredSize().height));
        allChatButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, refreshButton.getPreferredSize().height));
        refreshButton.setAlignmentX(Component.LEFT_ALIGNMENT);

        topSection.add(allChatButton);
        topSection.add(refreshButton);

        JLabel onlineLabel = new JLabel("  Online");
        onlineLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        onlineLabel.setForeground(new Color(160, 180, 210));
        onlineLabel.setBorder(BorderFactory.createEmptyBorder(10, 4, 5, 4));
        onlineLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        onlineLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, onlineLabel.getPreferredSize().height));
        topSection.add(onlineLabel);

        sidebar.add(topSection, BorderLayout.NORTH);

        // --- User List ---
        userList.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        userList.setBackground(CLR_SIDEBAR_BG);
        userList.setForeground(Color.WHITE);
        userList.setSelectionBackground(CLR_SIDEBAR_HOVER);
        userList.setSelectionForeground(Color.WHITE);
        userList.setFixedCellHeight(36);
        userList.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));

        // Custom renderer for user list with unread indicator
        userList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String username = (String) value;
                label.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                label.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
                label.setOpaque(true);

                if (username.equals(currentTarget)) {
                    label.setBackground(CLR_SIDEBAR_ACTIVE);
                } else if (isSelected) {
                    label.setBackground(CLR_SIDEBAR_HOVER);
                } else {
                    label.setBackground(CLR_SIDEBAR_BG);
                }
                label.setForeground(Color.WHITE);

                // Unread indicator
                Boolean hasUnread = unreadMap.get(username);
                if (hasUnread != null && hasUnread && !username.equals(currentTarget)) {
                    label.setText("● " + username);
                    label.setForeground(CLR_UNREAD);
                } else {
                    label.setText("  " + username);
                }

                return label;
            }
        });

        userList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int idx = userList.locationToIndex(e.getPoint());
                if (idx >= 0) {
                    String selected = userListModel.getElementAt(idx);
                    if (!selected.equals(clientName)) {
                        switchTarget(selected);
                    }
                }
            }
        });

        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(null);
        userScroll.setBackground(CLR_SIDEBAR_BG);
        userScroll.getViewport().setBackground(CLR_SIDEBAR_BG);
        sidebar.add(userScroll, BorderLayout.CENTER);

        // --- Logout Button ---
        logoutButton.setFont(new Font("Segoe UI Emoji", Font.BOLD, 13));
        logoutButton.setForeground(new Color(252, 165, 165));
        logoutButton.setBackground(new Color(45, 55, 72));
        logoutButton.setBorderPainted(false);
        logoutButton.setFocusPainted(false);
        logoutButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        logoutButton.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        logoutButton.setHorizontalAlignment(SwingConstants.LEFT);
        logoutButton.addActionListener(e -> doLogout());
        sidebar.add(logoutButton, BorderLayout.SOUTH);

        return sidebar;
    }

    /** Switch active conversation target */
    private void switchTarget(String target) {
        // Save current chat HTML
        saveChatHistory();

        currentTarget = target;

        // Clear unread
        unreadMap.put(target, false);
        userList.repaint();

        // Update header
        if (TARGET_ALL.equals(target)) {
            chatTargetLabel.setText("# All");
            allChatButton.setBackground(CLR_SIDEBAR_ACTIVE);
        } else {
            chatTargetLabel.setText("→ " + target);
            allChatButton.setBackground(CLR_SIDEBAR_BG);
        }
        userList.repaint();

        // Load target's chat history
        loadChatHistory(target);
        textField.requestFocus();
    }

    /** Save current chat content */
    private void saveChatHistory() {
        try {
            HTMLDocument doc = (HTMLDocument) chatPane.getDocument();
            StringWriter sw = new StringWriter();
            chatPane.getEditorKit().write(sw, doc, 0, doc.getLength());
            chatHistories.put(currentTarget, new StringBuilder(sw.toString()));
        } catch (Exception ignored) {}
    }

    /** Load chat history for a target */
    private void loadChatHistory(String target) {
        StringBuilder history = chatHistories.get(target);
        if (history != null) {
            chatPane.setText(history.toString());
            // Scroll to bottom
            SwingUtilities.invokeLater(() ->
                chatPane.setCaretPosition(chatPane.getDocument().getLength()));
        } else {
            resetChat();
            if (TARGET_ALL.equals(target)) {
                appendSystemMsg("Welcome to the public chat room!");
            } else {
                appendSystemMsg("Starting conversation with " + target);
            }
        }
    }

    private static void styleIconBtn(JButton btn, Font font) {
        btn.setFont(font);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    }

    private void resetChat() {
        chatPane.setText(
            "<html><body id='chatBody' style='" +
            "font-family:Arial,sans-serif;" +
            "background:#EBECF3;padding:10px;margin:0;'></body></html>");
    }

    private void setupEmojiPicker() {
        JPopupMenu popup = new JPopupMenu();
        popup.setBorder(BorderFactory.createLineBorder(CLR_DIVIDER));
        String[] emojis = {
            "\uD83D\uDE00", "\uD83D\uDE02", "\uD83E\uDD70", "\uD83D\uDE0E",
            "\uD83D\uDE22", "\uD83D\uDE21", "\uD83D\uDC4D", "\u2764\uFE0F",
            "\uD83C\uDF89", "\uD83D\uDD25", "\uD83E\uDD14", "\uD83D\uDE05",
            "\uD83D\uDE0D", "\uD83D\uDE4F", "\uD83D\uDCAF", "\u2705",
            "\uD83C\uDFB5", "\uD83E\uDD23"
        };
        JPanel grid = new JPanel(new GridLayout(3, 6, 2, 2));
        grid.setBackground(Color.WHITE);
        grid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        for (String em : emojis) {
            JButton b = new JButton(em);
            b.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 20));
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setContentAreaFilled(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> {
                textField.setText(textField.getText() + em);
                popup.setVisible(false);
                textField.requestFocus();
            });
            grid.add(b);
        }
        popup.add(grid);
        emojiButton.addActionListener(
            e -> popup.show(emojiButton, 0, -(int) popup.getPreferredSize().getHeight() - 4));
    }

    // =========================================================================
    // Send actions
    // =========================================================================
    private void sendText() {
        String msg = textField.getText().trim();
        if (!msg.isEmpty() && out != null) {
            if (TARGET_ALL.equals(currentTarget)) {
                // Public message
                out.println("TEXT:" + clientName + ": " + msg);
            } else {
                // Private message
                out.println("PRIVATE:" + clientName + ":" + currentTarget + ":" + msg);
            }
            textField.setText("");
        }
    }

    private void sendFile() {
        if (out == null) {
            JOptionPane.showMessageDialog(frame,
                "Not connected to server!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File file = fc.getSelectedFile();

        new Thread(() -> {
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                if (bytes.length > MAX_FILE_BYTES) {
                    SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(frame,
                            "File too large! Max 10 MB.", "Error", JOptionPane.ERROR_MESSAGE));
                    return;
                }
                String b64 = Base64.getEncoder().encodeToString(bytes);
                if (TARGET_ALL.equals(currentTarget)) {
                    out.println("FILE:" + clientName + ":" + file.getName() + ":" + b64);
                } else {
                    // For private file sending, we use the PRIVATE prefix with FILE content
                    out.println("PRIVATE:" + clientName + ":" + currentTarget + ":FILE:" + file.getName() + ":" + b64);
                }
                appendSystemMsg("Sent: " + file.getName()
                    + " (" + bytes.length / 1024 + " KB)");
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(frame,
                        "Cannot read file: " + ex.getMessage(), "Error",
                        JOptionPane.ERROR_MESSAGE));
            }
        }, "file-sender").start();
    }

    private void downloadFile(String fileId, byte[] data) {
        String name = fileId.contains("_")
            ? fileId.substring(fileId.indexOf('_') + 1) : fileId;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(name));
        if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try (FileOutputStream fos = new FileOutputStream(fc.getSelectedFile())) {
                fos.write(data);
                JOptionPane.showMessageDialog(frame, "File saved successfully!", "Success",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame, "Error saving file: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // =========================================================================
    // Refresh users
    // =========================================================================
    private void requestRefreshUsers() {
        if (out != null && connected) {
            out.println("REFRESH_USERS");
            appendSystemMsg("Refreshing user list...");
        } else {
            JOptionPane.showMessageDialog(frame,
                "Not connected to server!", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // Logout
    // =========================================================================
    private void doLogout() {
        int confirm = JOptionPane.showConfirmDialog(frame,
            "Are you sure you want to logout?", "Logout",
            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        // Send logout command to server
        if (out != null) {
            out.println("LOGOUT:" + clientName);
        }

        // Clean up connection
        connected = false;
        if (currentSocket != null) {
            try { currentSocket.close(); } catch (IOException ignored) {}
        }
        out = null;
        currentSocket = null;

        // Reset state
        SwingUtilities.invokeLater(() -> {
            chatHistories.clear();
            unreadMap.clear();
            userListModel.clear();
            currentTarget = TARGET_ALL;
            chatTargetLabel.setText("# All");
            allChatButton.setBackground(CLR_SIDEBAR_ACTIVE);
            resetChat();
            statusLabel.setText("  Logged out");
            statusLabel.setForeground(CLR_STATUS_OFF);
            titleLabel.setText("LightMes Chat");
            frame.setTitle("LightMes");
        });

        // Reconnect (show login dialog again)
        new Thread(this::connectToServer, "reconnect-thread").start();
    }

    // =========================================================================
    // HTML helpers
    // =========================================================================
    private static String escHtml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() * 2);
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            switch (cp) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                default:
                    if (cp > 0xFFFF) {
                        sb.append("&#x").append(Integer.toHexString(cp)).append(';');
                    } else {
                        sb.appendCodePoint(cp);
                    }
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private String buildFileHtml(String fileName, String fileId, byte[] data) {
        boolean isImg = fileName.toLowerCase()
            .matches(".*\\.(png|jpg|jpeg|gif|bmp|webp)$");
        if (isImg && data != null) {
            try {
                File tmp = new File(System.getProperty("java.io.tmpdir"),
                    "lm_" + Math.abs(fileId.hashCode()) + "_" + fileName);
                if (!tmp.exists()) Files.write(tmp.toPath(), data);
                String url = tmp.toURI().toURL().toString();
                return "<img src='" + url + "' width='200'><br>"
                     + "<a href='" + fileId + "' style='color:#1A56A0;font-size:11px;'>"
                     + "Download original</a>";
            } catch (IOException ex) {
                // fall through to generic display
            }
        }
        return "<b>" + escHtml(fileName) + "</b><br>"
             + "<a href='" + fileId + "' style='color:#1A56A0;'>"
             + "Click to download</a>";
    }

    private void appendToChat(String sender, String text,
                               boolean isFile, String fileId, byte[] fileData) {
        SwingUtilities.invokeLater(() -> {
            try {
                HTMLDocument  doc  = (HTMLDocument)  chatPane.getDocument();
                HTMLEditorKit kit  = (HTMLEditorKit) chatPane.getEditorKit();
                String time    = LocalTime.now().format(TIME_FMT);
                String content = isFile ? buildFileHtml(text, fileId, fileData) : escHtml(text);
                String html;

                if ("System".equalsIgnoreCase(sender)) {
                    html = "<table width='100%' border='0' cellpadding='2' cellspacing='0'>"
                         + "<tr><td align='center'>"
                         + "<font face='Arial' size='2' color='#7A828F'>"
                         + "<i>" + content + "</i></font></td></tr></table>";

                } else if (sender.equals(clientName)) {
                    html = "<table width='100%' border='0' cellpadding='0' cellspacing='2'>"
                         + "<tr><td align='right'>"
                         + "<table bgcolor='#DCF8C6' border='0' cellpadding='8' cellspacing='0'>"
                         + "<tr><td>"
                         + "<font face='Segoe UI Emoji,Arial' size='3' color='#000000'>"
                         + content + "</font>"
                         + "&nbsp;<font size='1' color='#A0A8B0'>" + time + "</font>"
                         + "</td></tr></table></td></tr></table>";

                } else {
                    String safeSender = escHtml(sender);
                    html = "<table width='100%' border='0' cellpadding='0' cellspacing='2'>"
                         + "<tr><td align='left'>"
                         + "<table bgcolor='#FFFFFF' border='0' cellpadding='0' cellspacing='0' width='270'>"
                         + "<tr><td style='border-bottom:1px solid #E0E4ED;padding:5px 10px 4px 10px;'>"
                         + "<font face='Arial' size='2' color='#1A56A0'><b>"
                         + safeSender + "</b></font></td></tr>"
                         + "<tr><td style='padding:6px 10px 6px 10px;'>"
                         + "<font face='Segoe UI Emoji,Arial' size='3' color='#000000'>"
                         + content + "</font>"
                         + "&nbsp;<font size='1' color='#A0A8B0'>" + time + "</font>"
                         + "</td></tr></table></td></tr></table>";
                }

                kit.insertHTML(doc, doc.getLength(), html, 0, 0, null);
                chatPane.setCaretPosition(doc.getLength());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    private void appendSystemMsg(String msg) {
        appendToChat("System", msg, false, null, null);
    }

    // =========================================================================
    // Server connection
    // =========================================================================
    private void connectToServer() {
        while (true) {
            // 1. Show login/register form
            JPanel authPanel = new JPanel(new GridLayout(3, 2, 8, 8));
            JTextField userField = new JTextField(16);
            JPasswordField passField = new JPasswordField(16);
            String[] options = {"Login", "Register"};
            JComboBox<String> modeBox = new JComboBox<>(options);
            authPanel.add(new JLabel("Username:")); authPanel.add(userField);
            authPanel.add(new JLabel("Password:"));  authPanel.add(passField);
            authPanel.add(new JLabel("Mode:"));    authPanel.add(modeBox);
            
            int ok = JOptionPane.showConfirmDialog(frame, authPanel, "Login / Register", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) System.exit(0);
            
            String username = userField.getText().trim();
            String password = new String(passField.getPassword());
            boolean isRegister = modeBox.getSelectedIndex() == 1;
            
            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please enter both username and password!", "Missing info", JOptionPane.ERROR_MESSAGE);
                continue;
            }

            // 2. Enter IP/port
            JTextField ipField   = new JTextField("127.0.0.1", 17);
            JTextField portField = new JTextField("6666", 7);
            JPanel addrPanel = new JPanel(new GridLayout(2, 2, 8, 8));
            addrPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
            addrPanel.add(new JLabel("Server IP:"));  addrPanel.add(ipField);
            addrPanel.add(new JLabel("Port:"));        addrPanel.add(portField);
            
            int choice = JOptionPane.showConfirmDialog(frame, addrPanel, "Connect to Server", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) continue;
            
            String ip = ipField.getText().trim().isEmpty() ? "127.0.0.1" : ipField.getText().trim();
            int port = 6666;
            try { port = Integer.parseInt(portField.getText().trim()); } catch (NumberFormatException ignored) {}

            // 3. Connect and authenticate
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(ip, port), 5000);
                
                // Set read timeout to avoid hanging indefinitely waiting for auth response
                socket.setSoTimeout(10000);
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), true);
                
                // Send auth command
                if (isRegister) {
                    out.println("AUTH:REGISTER:" + username + ":" + password);
                } else {
                    out.println("AUTH:LOGIN:" + username + ":" + password);
                }
                
                // Wait for auth response
                String resp = reader.readLine();
                if (resp != null && resp.equals("AUTH:OK")) {
                    // Disable timeout for normal chat (blocking reads)
                    socket.setSoTimeout(0);
                    
                    clientName = username;
                    currentSocket = socket;
                    connected = true;

                    SwingUtilities.invokeLater(() -> {
                        frame.setTitle("LightMes - " + clientName);
                        titleLabel.setText("LightMes - " + clientName);
                        statusLabel.setText("Online: " + clientName);
                        statusLabel.setForeground(CLR_STATUS_ON);
                        resetChat();
                        appendSystemMsg("Welcome to the public chat room!");
                    });
                    
                    out.println("TEXT:System: " + clientName + " has joined the chat!");
                    
                    // Start message receiver thread
                    new Thread(() -> {
                        try {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                processLine(line);
                            }
                        } catch (IOException ex) {
                            if (connected) {
                                handleDisconnect(ex.getMessage());
                            }
                        }
                    }, "reader-thread").start();
                    
                    break; // Exit the while(true) loop on success
                } else {
                    socket.close();
                    out = null;
                    JOptionPane.showMessageDialog(frame, isRegister ? "Registration failed! Account may already exist." : "Wrong username or password!", "Auth Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (SocketTimeoutException ex) {
                out = null;
                JOptionPane.showMessageDialog(frame, "Server not responding. Please try again.", "Timeout", JOptionPane.ERROR_MESSAGE);
            } catch (IOException ex) {
                out = null;
                JOptionPane.showMessageDialog(frame, "Cannot connect to server: " + ex.getMessage(), "Connection Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void handleDisconnect(String error) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Disconnected");
            statusLabel.setForeground(CLR_STATUS_OFF);
        });
        appendSystemMsg("Disconnected from server: " + error);
        out = null;
        connected = false;
    }

    /**
     * Parses a server line.
     * TEXT format   : TEXT:senderName: message body
     * FILE format   : FILE:senderName:filename:base64data
     * PRIVATE format: PRIVATE:sender:target:message (or PRIVATE:sender:target:FILE:filename:base64)
     * USERLIST      : USERLIST:user1,user2,...
     */
    private void processLine(String line) {
        if (line.startsWith("USERLIST:")) {
            String payload = line.substring(9);
            SwingUtilities.invokeLater(() -> {
                userListModel.clear();
                if (!payload.isEmpty()) {
                    String[] users = payload.split(",");
                    for (String u : users) {
                        if (!u.equals(clientName)) {
                            userListModel.addElement(u);
                        }
                    }
                }
            });
        } else if (line.startsWith("PRIVATE:")) {
            // PRIVATE:sender:target:message
            String payload = line.substring(8);
            int s1 = payload.indexOf(':');
            if (s1 < 0) return;
            int s2 = payload.indexOf(':', s1 + 1);
            if (s2 < 0) return;

            String sender  = payload.substring(0, s1);
            String target  = payload.substring(s1 + 1, s2);
            String message = payload.substring(s2 + 1);

            // Determine which conversation this belongs to
            String conversationWith = sender.equals(clientName) ? target : sender;

            // Check if message contains a private file
            if (message.startsWith("FILE:")) {
                String filePart = message.substring(5);
                int fs1 = filePart.indexOf(':');
                if (fs1 < 0) return;
                String fileName = filePart.substring(0, fs1);
                String b64 = filePart.substring(fs1 + 1);

                try {
                    byte[] data = Base64.getDecoder().decode(b64);
                    String fileId = System.currentTimeMillis() + "_" + fileName;
                    fileStorage.put(fileId, data);

                    if (conversationWith.equals(currentTarget)) {
                        appendToChat(sender, fileName, true, fileId, data);
                    } else {
                        saveMessageToHistory(conversationWith, sender, fileName, true, fileId, data);
                        markUnread(conversationWith);
                    }
                } catch (IllegalArgumentException e) {
                    appendSystemMsg("Error decoding file from " + sender);
                }
            } else {
                // Regular private text message
                if (conversationWith.equals(currentTarget)) {
                    appendToChat(sender, message, false, null, null);
                } else {
                    saveMessageToHistory(conversationWith, sender, message, false, null, null);
                    markUnread(conversationWith);
                }
            }
        } else if (line.startsWith("TEXT:")) {
            String body = line.substring(5);
            int sep = body.indexOf(": ");
            if (sep > 0) {
                String sender = body.substring(0, sep);
                String message = body.substring(sep + 2);
                if (TARGET_ALL.equals(currentTarget)) {
                    appendToChat(sender, message, false, null, null);
                } else {
                    saveMessageToHistory(TARGET_ALL, sender, message, false, null, null);
                    markUnread(TARGET_ALL);
                }
            } else {
                // System message — always show in current view
                if (TARGET_ALL.equals(currentTarget)) {
                    appendSystemMsg(body);
                } else {
                    saveMessageToHistory(TARGET_ALL, "System", body, false, null, null);
                }
            }
        } else if (line.startsWith("FILE:")) {
            String payload = line.substring(5);
            int s1 = payload.indexOf(':');
            if (s1 < 0) return;
            int s2 = payload.indexOf(':', s1 + 1);
            if (s2 < 0) return;

            String sender   = payload.substring(0, s1);
            String fileName = payload.substring(s1 + 1, s2);
            String b64      = payload.substring(s2 + 1);

            try {
                byte[] data   = Base64.getDecoder().decode(b64);
                String fileId = System.currentTimeMillis() + "_" + fileName;
                fileStorage.put(fileId, data);
                if (TARGET_ALL.equals(currentTarget)) {
                    appendToChat(sender, fileName, true, fileId, data);
                } else {
                    saveMessageToHistory(TARGET_ALL, sender, fileName, true, fileId, data);
                    markUnread(TARGET_ALL);
                }
            } catch (IllegalArgumentException e) {
                appendSystemMsg("Error decoding file '" + fileName + "' from " + sender);
            }
        }
    }

    /** Save a message to the history of a conversation that is NOT currently displayed */
    private void saveMessageToHistory(String target, String sender, String text,
                                       boolean isFile, String fileId, byte[] fileData) {
        // Build the HTML snippet
        String time = LocalTime.now().format(TIME_FMT);
        String content = isFile ? buildFileHtml(text, fileId, fileData) : escHtml(text);
        String html;

        if ("System".equalsIgnoreCase(sender)) {
            html = "<table width='100%' border='0' cellpadding='2' cellspacing='0'>"
                 + "<tr><td align='center'>"
                 + "<font face='Arial' size='2' color='#7A828F'>"
                 + "<i>" + content + "</i></font></td></tr></table>";
        } else if (sender.equals(clientName)) {
            html = "<table width='100%' border='0' cellpadding='0' cellspacing='2'>"
                 + "<tr><td align='right'>"
                 + "<table bgcolor='#DCF8C6' border='0' cellpadding='8' cellspacing='0'>"
                 + "<tr><td>"
                 + "<font face='Segoe UI Emoji,Arial' size='3' color='#000000'>"
                 + content + "</font>"
                 + "&nbsp;<font size='1' color='#A0A8B0'>" + time + "</font>"
                 + "</td></tr></table></td></tr></table>";
        } else {
            String safeSender = escHtml(sender);
            html = "<table width='100%' border='0' cellpadding='0' cellspacing='2'>"
                 + "<tr><td align='left'>"
                 + "<table bgcolor='#FFFFFF' border='0' cellpadding='0' cellspacing='0' width='270'>"
                 + "<tr><td style='border-bottom:1px solid #E0E4ED;padding:5px 10px 4px 10px;'>"
                 + "<font face='Arial' size='2' color='#1A56A0'><b>"
                 + safeSender + "</b></font></td></tr>"
                 + "<tr><td style='padding:6px 10px 6px 10px;'>"
                 + "<font face='Segoe UI Emoji,Arial' size='3' color='#000000'>"
                 + content + "</font>"
                 + "&nbsp;<font size='1' color='#A0A8B0'>" + time + "</font>"
                 + "</td></tr></table></td></tr></table>";
        }

        // Append HTML to stored history
        StringBuilder history = chatHistories.get(target);
        if (history == null) {
            String base = "<html><body id='chatBody' style='"
                        + "font-family:Arial,sans-serif;"
                        + "background:#EBECF3;padding:10px;margin:0;'>"
                        + html
                        + "</body></html>";
            chatHistories.put(target, new StringBuilder(base));
        } else {
            // Insert before </body></html>
            int closeIdx = history.lastIndexOf("</body>");
            if (closeIdx >= 0) {
                history.insert(closeIdx, html);
            } else {
                history.append(html);
            }
        }
    }

    /** Mark a conversation as having unread messages */
    private void markUnread(String target) {
        if (!target.equals(currentTarget)) {
            unreadMap.put(target, true);
            SwingUtilities.invokeLater(() -> {
                if (TARGET_ALL.equals(target)) {
                    allChatButton.setForeground(CLR_UNREAD);
                    allChatButton.setText("● All Chat");
                }
                userList.repaint();
            });
        }
    }

    // =========================================================================
    // Entry point
    // =========================================================================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ChatClientGUI client = new ChatClientGUI();
            client.frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            client.frame.setSize(680, 720);
            client.frame.setMinimumSize(new Dimension(520, 520));
            client.frame.setLocationRelativeTo(null);
            client.frame.setVisible(true);
            
            // Run connection in a separate thread to avoid blocking the EDT
            new Thread(client::connectToServer, "connection-thread").start();
        });
    }
}