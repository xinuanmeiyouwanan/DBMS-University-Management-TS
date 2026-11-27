package edu.management;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Properties;

import edu.management.Accounts.Credential;
import edu.management.Accounts.Role;
import edu.management.Accounts.Session;

// 本地文件保存登录凭据，学生和教师账号通过学号或工号关联到数据库。
final class LocalAccounts {
    private final Path file;
    private final Path lockFile;
    private final Database database;

    LocalAccounts(Path file, Database database) {
        this.file = file.toAbsolutePath().normalize();
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
        this.database = database;
    }

    Credential find(String login) throws IOException {
        String value = read().getProperty(login);
        if (value == null) {
            return null;
        }
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 5) {
                throw new IllegalArgumentException();
            }
            Role role = Role.valueOf(parts[0]);
            String number = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] hash = Base64.getDecoder().decode(parts[4]);
            if ((role == Role.ADMIN) != number.isEmpty() || iterations <= 0 || salt.length != 16 || hash.length != 32) {
                throw new IllegalArgumentException();
            }
            Session session = new Session(login, role, role == Role.STUDENT ? number : null,
                    role == Role.TEACHER ? number : null);
            return new Credential(session, salt, hash, iterations);
        } catch (IllegalArgumentException ex) {
            throw new IOException("本地账号文件格式错误，请维护人员检查配置。", ex);
        }
    }

    void create(String login, Role role, String memberNo, byte[] salt, byte[] hash, int iterations)
            throws IOException, SQLException {
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            Properties accounts = read();
            if (accounts.containsKey(login)) {
                throw new IllegalArgumentException("登录名已存在。");
            }
            String number = "";
            if (role != Role.ADMIN) {
                number = findMember(role, memberNo);
                if (number == null) {
                    throw new IllegalArgumentException("学号或工号不存在。");
                }
                if (hasBinding(accounts, role, number)) {
                    throw new IllegalArgumentException("该学号或工号已绑定其他账号。");
                }
            }
            accounts.setProperty(login, binding(role, number) + iterations + ":"
                    + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash));
            Path tmp = Files.createTempFile(file.getParent(), "accounts-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    accounts.store(writer, null);
                }
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    boolean deleteStudent(StudentDao students, String studentNo) throws IOException, SQLException {
        // 删除学生时持有共享锁，防止开户操作同时绑定该学号。
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ);
                FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {
            String number = findMember(Role.STUDENT, studentNo);
            if (number == null) {
                return false;
            }
            if (hasBinding(read(), Role.STUDENT, number)) {
                throw new IllegalArgumentException("该学生已绑定登录账号，无法删除。");
            }
            return students.delete(number);
        }
    }

    String findMember(Role role, String number) throws SQLException {
        String sql = role == Role.STUDENT ? "SELECT student_no FROM student WHERE student_no = ?"
                : "SELECT teacher_no FROM teacher WHERE teacher_no = ?";
        try (Connection conn = database.open(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, number);
            try (ResultSet rows = stmt.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private Properties read() throws IOException {
        Properties entries = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            entries.load(reader);
        } catch (NoSuchFileException e) {
            // 首次开户时才创建账号文件。
        }
        return entries;
    }

    private static String binding(Role role, String number) {
        return role.name() + ":" + Base64.getEncoder().encodeToString(number.getBytes(StandardCharsets.UTF_8)) + ":";
    }

    private static boolean hasBinding(Properties accounts, Role role, String number) {
        String prefix = binding(role, number);
        return accounts.stringPropertyNames().stream()
                .anyMatch(login -> accounts.getProperty(login).startsWith(prefix));
    }
}
