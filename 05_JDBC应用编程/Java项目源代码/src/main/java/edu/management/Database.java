package edu.management;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public final class Database {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private final String url;
    private final String user;
    private final String password;
    private final LocalDateTime selectionStart;
    private final LocalDateTime selectionEnd;
    private final Path localAccountsFile;
    private final Set<String> configuredArchivedClasses = new HashSet<>();

    private Database(Properties config, Path configPath) {
        url = config.getProperty("url", "").strip();
        user = config.getProperty("user", "").strip();
        password = config.getProperty("password");
        if (url.isEmpty() || user.isEmpty() || password == null) {
            throw new IllegalArgumentException("数据库配置需要 url、user 和 password 三项。");
        }
        String start = config.getProperty("selection.start", "").strip();
        String end = config.getProperty("selection.end", "").strip();
        try {
            selectionStart = start.isEmpty() ? null : LocalDateTime.parse(start);
            selectionEnd = end.isEmpty() ? null : LocalDateTime.parse(end);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("选退课窗口须使用日期时间格式，如 2026-09-01T08:00。", e);
        }
        if ((selectionStart == null) != (selectionEnd == null)
                || (selectionStart != null && !selectionStart.isBefore(selectionEnd))) {
            throw new IllegalArgumentException("请同时配置选退课起止时间，并确保开始时间早于结束时间。");
        }
        for (String value : config.getProperty("grade.archived.classes", "").split(",")) {
            if (!value.isBlank()) {
                configuredArchivedClasses.add(value.strip());
            }
        }
        String file = config.getProperty("accounts.file", ".work/accounts.properties");
        localAccountsFile = configPath.toAbsolutePath().resolveSibling(file).normalize();
    }

    public static Database load(Path path) throws IOException {
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        return new Database(config, path);
    }

    Path localAccountsFile() {
        return localAccountsFile;
    }

    Set<String> archivedClassNumbers(Connection conn) throws SQLException {
        if (configuredArchivedClasses.isEmpty()) {
            return Set.of();
        }
        // 由教学班号列的排序规则匹配配置值，统一使用库内保存的班号。
        String placeholders = String.join(", ", Collections.nCopies(configuredArchivedClasses.size(), "?"));
        String sql = "SELECT class_no FROM teaching_class WHERE class_no IN (" + placeholders + ")";
        try (PreparedStatement query = conn.prepareStatement(sql)) {
            int index = 1;
            for (String classNo : configuredArchivedClasses) {
                query.setString(index++, classNo);
            }
            try (ResultSet rows = query.executeQuery()) {
                Set<String> numbers = new HashSet<>();
                while (rows.next()) {
                    numbers.add(rows.getString("class_no"));
                }
                return numbers;
            }
        }
    }

    void requireOpenGradeBook(Connection conn, String storedClassNo) throws SQLException {
        if (archivedClassNumbers(conn).contains(storedClassNo)) {
            throw new IllegalArgumentException("该教学班成绩已归档。");
        }
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    public boolean isSelectionOpen() {
        LocalDateTime now = LocalDateTime.now(CAMPUS_ZONE);
        return selectionStart != null && !now.isBefore(selectionStart) && now.isBefore(selectionEnd);
    }
}
