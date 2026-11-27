package edu.management;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class GradeBook {
    record ClassInfo(String classNo, String course, String semester, boolean archived) {
    }

    record RosterEntry(String studentNo, String name, BigDecimal score, int attempt) {
    }

    record ClassStats(String classNo, String course, int enrolled, int graded,
            BigDecimal average, BigDecimal highest, BigDecimal passRate) {
    }

    private final Database database;

    GradeBook(Database database) {
        this.database = database;
    }

    List<ClassInfo> classes(String teacherNo) throws SQLException {
        String sql = """
                SELECT tc.class_no, c.course_name, tc.semester
                FROM teaching_class tc JOIN course c ON c.course_no = tc.course_no
                WHERE tc.teacher_no = ? ORDER BY tc.semester DESC, tc.class_no
                """;
        try (Connection conn = database.open(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            var archived = database.archivedClassNumbers(conn);
            stmt.setString(1, teacherNo);
            try (ResultSet rows = stmt.executeQuery()) {
                List<ClassInfo> classes = new ArrayList<>();
                while (rows.next()) {
                    String classNo = rows.getString("class_no");
                    classes.add(new ClassInfo(classNo, rows.getString("course_name"),
                            rows.getString("semester"), archived.contains(classNo)));
                }
                return classes;
            }
        }
    }

    List<ClassStats> statistics(String teacherNo) throws SQLException {
        // 及格和不及格分别按 1 和 0 计算，空成绩不计入及格率的分母。
        String sql = """
                SELECT tc.class_no, c.course_name, COUNT(e.student_no) AS enrolled,
                       COUNT(e.score) AS graded, ROUND(AVG(e.score), 2) AS average_score,
                       MAX(e.score) AS highest_score, ROUND(100 * AVG(e.score >= 60), 2) AS pass_rate
                FROM teaching_class tc
                JOIN course c ON c.course_no = tc.course_no
                LEFT JOIN enrollment e ON e.class_no = tc.class_no
                WHERE tc.teacher_no = ?
                GROUP BY tc.class_no, c.course_name
                ORDER BY tc.class_no
                """;
        try (Connection conn = database.open(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, teacherNo);
            try (ResultSet rows = stmt.executeQuery()) {
                List<ClassStats> stats = new ArrayList<>();
                while (rows.next()) {
                    stats.add(new ClassStats(rows.getString("class_no"), rows.getString("course_name"),
                            rows.getInt("enrolled"), rows.getInt("graded"), rows.getBigDecimal("average_score"),
                            rows.getBigDecimal("highest_score"), rows.getBigDecimal("pass_rate")));
                }
                return stats;
            }
        }
    }

    List<RosterEntry> roster(String teacherNo, String classNo) throws SQLException {
        String sql = """
                SELECT e.student_no, s.name, e.score, e.attempt
                FROM teaching_class tc LEFT JOIN enrollment e ON e.class_no = tc.class_no
                LEFT JOIN student s ON s.student_no = e.student_no
                WHERE tc.class_no = ? AND tc.teacher_no = ? ORDER BY e.student_no
                """;
        try (Connection conn = database.open(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, classNo);
            stmt.setString(2, teacherNo);
            try (ResultSet rows = stmt.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("教学班不存在或不属于当前教师。");
                }
                List<RosterEntry> roster = new ArrayList<>();
                do {
                    String id = rows.getString("student_no");
                    if (id != null) {
                        roster.add(new RosterEntry(id, rows.getString("name"), rows.getBigDecimal("score"),
                                rows.getInt("attempt")));
                    }
                } while (rows.next());
                return roster;
            }
        }
    }

    void saveScore(String teacherNo, String classNo, String studentNo, String scoreInput, boolean retake)
            throws SQLException {
        if (!scoreInput.matches("[0-9]{1,3}(\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("成绩须为 0 至 100 的数字，最多两位小数。");
        }
        BigDecimal score = new BigDecimal(scoreInput);
        if (score.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("成绩须在 0 至 100 之间。");
        }
        try (Connection conn = database.open()) {
            conn.setAutoCommit(false);
            try {
                String storedClassNo = lockClass(conn, classNo, teacherNo);
                database.requireOpenGradeBook(conn, storedClassNo);
                String sql = "UPDATE enrollment SET score = ? WHERE class_no = ? AND student_no = ?";
                if (retake) {
                    checkRetake(conn, storedClassNo, studentNo);
                    sql = "UPDATE enrollment SET score = ?, attempt = attempt + 1 WHERE class_no = ? AND student_no = ?";
                }
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setBigDecimal(1, score);
                    stmt.setString(2, classNo);
                    stmt.setString(3, studentNo);
                    if (stmt.executeUpdate() != 1) {
                        throw new IllegalArgumentException("未找到该学生在本教学班的选课记录。");
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                rollback(conn, e);
                throw e;
            }
        }
    }

    private static void checkRetake(Connection conn, String classNo, String studentNo) throws SQLException {
        String sql = "SELECT score, attempt FROM enrollment WHERE class_no = ? AND student_no = ? FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, classNo);
            stmt.setString(2, studentNo);
            try (ResultSet row = stmt.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("未找到该学生在本教学班的选课记录。");
                }
                if (row.getBigDecimal("score") == null) {
                    throw new IllegalArgumentException("尚未录入原成绩，不能登记重修。");
                }
                if (row.getInt("attempt") == 255) {
                    throw new IllegalArgumentException("修读次数已达上限 255，不能登记重修。");
                }
            }
        }
    }

    private static void rollback(Connection conn, Exception failure) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static String lockClass(Connection conn, String classNo, String teacherNo) throws SQLException {
        String sql = "SELECT class_no FROM teaching_class WHERE class_no = ? AND teacher_no = ? FOR UPDATE";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, classNo);
            stmt.setString(2, teacherNo);
            try (ResultSet row = stmt.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalArgumentException("教学班不存在或无权操作。");
                }
                return row.getString("class_no");
            }
        }
    }
}
