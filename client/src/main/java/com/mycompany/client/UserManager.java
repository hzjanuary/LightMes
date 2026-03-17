package com.mycompany.client;

import java.sql.*;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class UserManager {
    private static final String DB_PATH;
    private Connection conn;

    static {
        // Tìm đường dẫn DB linh hoạt: thử gốc hoặc thư mục client
        String userDir = System.getProperty("user.dir");
        File dbFile = new File(userDir, "client" + File.separator + "db" + File.separator + "users.db");
        if (!dbFile.getParentFile().exists()) {
            // Thử trường hợp chạy từ trong thư mục client
            dbFile = new File(userDir, "db" + File.separator + "users.db");
        }
        DB_PATH = dbFile.getAbsolutePath();
    }

    public UserManager() {
        try {
            File dbFile = new File(DB_PATH);
            dbFile.getParentFile().mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS users (username TEXT PRIMARY KEY, password TEXT)");
            }
        } catch (SQLException e) {
            System.err.println("[UserManager] Cannot open DB: " + e.getMessage());
            throw new RuntimeException("Cannot open DB: " + e.getMessage());
        }
    }

    public boolean register(String username, String password) {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users VALUES (?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, password);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("[UserManager] Register failed for '" + username + "': " + e.getMessage());
            return false;
        }
    }

    public boolean login(String username, String password) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE username=? AND password=?")) {
            ps.setString(1, username);
            ps.setString(2, password);
            ResultSet rs = ps.executeQuery();
            boolean ok = rs.next();
            if (!ok) System.err.println("[UserManager] Login failed for '" + username + "'");
            return ok;
        } catch (SQLException e) {
            System.err.println("[UserManager] Login error for '" + username + "': " + e.getMessage());
            return false;
        }
    }

    public Set<String> getAllUsers() {
        Set<String> users = new HashSet<>();
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT username FROM users");
            while (rs.next()) users.add(rs.getString(1));
        } catch (SQLException e) {
            System.err.println("[UserManager] getAllUsers error: " + e.getMessage());
        }
        return users;
    }
}
