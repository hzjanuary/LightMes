# LightMes

Ứng dụng chat LAN viết bằng Java Swing, gồm:
- 1 tiến trình server trung tâm
- Nhiều client kết nối qua IP + port

Hỗ trợ **chat phòng chung (All)**, **chat riêng 1-1 (Private)**, gửi emoji, gửi file/ảnh Base64, đăng nhập/đăng ký tài khoản SQLite, và đăng xuất.

## 1) Cấu trúc project

```text
LightMes/
  README.md
  client/
    pom.xml
    db/users.db              ← SQLite database (tự tạo khi chạy lần đầu)
    src/main/java/com/mycompany/client/
      ChatServer.java        ← Server trung tâm
      ChatClientGUI.java     ← Client GUI (Swing)
      UserManager.java       ← Quản lý tài khoản (SQLite)
      User.java              ← Model User
```

## 2) Công nghệ sử dụng

- Java 21+ (tested with Java 25)
- Java Swing (UI)
- Socket TCP
- SQLite (lưu tài khoản qua `sqlite-jdbc`)
- Maven (quản lý build & dependency)

## 3) Tính năng

### Chat
- **Chat phòng chung (All)**: Tất cả user đăng nhập đều thấy tin nhắn — broadcast.
- **Chat riêng 1-1 (Private)**: Click vào user trong sidebar → gửi tin chỉ 2 người thấy, mỗi cuộc trò chuyện có lịch sử riêng.
- Hỗ trợ **emoji** trong tin nhắn.
- Gửi **file và ảnh** (Base64), phía nhận click để tải về. Ảnh được preview inline.
- UI chat dạng **bubble**, phân biệt tin của mình (xanh lá, bên phải) / người khác (trắng, bên trái) / hệ thống (giữa, xám).

### Tài khoản & Kết nối
- **Đăng ký / Đăng nhập** tài khoản (lưu SQLite trên server).
- **Đăng xuất**: Ngắt kết nối, quay lại form đăng nhập — có thể đăng nhập lại tài khoản khác.
- Client nhập động **Server IP + Port** khi kết nối.

### Sidebar
- **Danh sách user online** — tự cập nhật khi có người join/rời.
- **Chấm đỏ unread** (●) hiển thị khi có tin nhắn chưa đọc ở conversation khác.
- Nút chuyển nhanh giữa chat All và chat Private.

### Server
- Broadcast tin nhắn public theo **hàng đợi riêng** từng client (giảm nghẽn).
- Tự broadcast **danh sách user online** (`USERLIST:`) khi có join/disconnect.
- Route **tin nhắn private** chỉ tới sender + target.

## 4) Chạy ứng dụng

### Cách A: chạy trong VS Code / IDE

1. Chạy class `ChatServer` trước (máy server).
2. Chạy class `ChatClientGUI` trên từng máy user.
3. Khi mở client:
	- Chọn Đăng nhập hoặc Đăng ký
	- Nhập tài khoản + mật khẩu
	- Nhập IP server (LAN IP của máy chạy server)
	- Nhập port (mặc định `6666`)

### Cách B: chạy bằng javac/java

Từ thư mục `client`:

```powershell
# Compile (cần sqlite-jdbc JAR trong classpath)
javac -encoding UTF-8 -d target/classes -cp "path/to/sqlite-jdbc-3.45.3.0.jar" src/main/java/com/mycompany/client/*.java

# Run server (máy server)
java -cp "target/classes;path/to/sqlite-jdbc-3.45.3.0.jar" com.mycompany.client.ChatServer

# Run client (mỗi máy user)
java -cp "target/classes;path/to/sqlite-jdbc-3.45.3.0.jar" com.mycompany.client.ChatClientGUI
```

### Cách C: chạy bằng Maven

```powershell
# Compile
mvn compile

# Run server
mvn exec:java -Dexec.mainClass="com.mycompany.client.ChatServer"

# Run client
mvn exec:java -Dexec.mainClass="com.mycompany.client.ChatClientGUI"
```

## 5) Chạy trên nhiều máy (1 server + N client)

1. Đảm bảo các máy cùng mạng LAN.
2. Trên máy server:
	- Chạy `ChatServer`
	- Ghi lại địa chỉ LAN IP server (ví dụ `192.168.1.10`)
3. Mở firewall cho port server (mặc định `6666`) nếu cần.
4. Trên mỗi máy user:
	- Chạy `ChatClientGUI`
	- Đăng ký tài khoản (lần đầu) hoặc đăng nhập
	- Nhập đúng IP server + port
5. Test:
	- Gửi text, emoji ở phòng chung (All)
	- Click user trong sidebar → gửi tin riêng (Private)
	- Gửi file (`.pdf`, `.txt`, ...) và ảnh (`.png`, `.jpg`, ...)
	- Đăng xuất → đăng nhập lại

## 6) Protocol

| Prefix | Hướng | Mô tả |
|--------|-------|-------|
| `AUTH:LOGIN:user:pass` | Client → Server | Đăng nhập |
| `AUTH:REGISTER:user:pass` | Client → Server | Đăng ký |
| `AUTH:OK` / `AUTH:FAIL` | Server → Client | Phản hồi xác thực |
| `TEXT:sender: message` | Broadcast | Tin nhắn phòng chung |
| `FILE:sender:filename:base64` | Broadcast | File phòng chung |
| `PRIVATE:sender:target:message` | Server → sender+target | Tin nhắn riêng 1-1 |
| `USERLIST:user1,user2,...` | Broadcast | Danh sách user online |
| `LOGOUT:username` | Client → Server | Yêu cầu đăng xuất |

- File giới hạn tối đa **10 MB** phía client gửi.
- Emoji trong HTML được encode dạng entity (`&#x...;`) để hiển thị ổn định với Swing HTML renderer.
- Server **không giữ lịch sử** tin nhắn; chỉ relay message theo thời gian thực.

## 7) Hạn chế hiện tại

- Chưa có lưu lịch sử chat vào database.
- Chưa có mã hóa end-to-end.
- Mật khẩu lưu plaintext trong SQLite (chưa hash).
- Chưa hỗ trợ group chat (nhóm tùy chỉnh).

## 8) Hướng phát triển tiếp theo

- Hash mật khẩu (BCrypt/Argon2).
- Lưu lịch sử chat/file metadata vào DB.
- Thêm group chat (tạo nhóm tùy chỉnh).
- Bổ sung retry/chunking cho file lớn.
- Tách protocol rõ ràng hơn (JSON framing) để mở rộng tính năng.