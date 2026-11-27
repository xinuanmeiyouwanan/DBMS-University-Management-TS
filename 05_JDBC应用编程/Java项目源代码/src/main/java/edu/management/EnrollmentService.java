package edu.management;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class EnrollmentService {
    private final Database db;

    public EnrollmentService(Database db) {
        this.db = db;
    }

    public void enroll(String studentNo, String classNo) throws SQLException {
        changeEnrollment(studentNo, classNo, true);
    }

    public void withdraw(String studentNo, String classNo) throws SQLException {
        changeEnrollment(studentNo, classNo, false);
    }

    private void changeEnrollment(String studentNo, String classNo, boolean enrolling) throws SQLException {
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                // 先锁学生以串行校验本人的课表，再锁教学班以串行分配名额。
                lockStudent(conn, studentNo);
                TeachingClass target = lockClass(conn, classNo);
                if (!db.isSelectionOpen()) {
                    throw new IllegalArgumentException("当前不在选退课时间窗口内。");
                }
                db.requireOpenGradeBook(conn, target.classNo());

                if (enrolling) {
                    addEnrollment(conn, studentNo, classNo, target);
                } else {
                    removeEnrollment(conn, studentNo, classNo);
                }
                String updateCountSql = enrolling
                        ? "UPDATE teaching_class SET enrolled_count = enrolled_count + 1 WHERE class_no = ?"
                        : "UPDATE teaching_class SET enrolled_count = enrolled_count - 1 WHERE class_no = ?";
                try (PreparedStatement update = conn.prepareStatement(updateCountSql)) {
                    update.setString(1, classNo);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("教学班人数更新失败。");
                    }
                }
                conn.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private static void lockStudent(Connection conn, String studentNo) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT student_no FROM student WHERE student_no = ? FOR UPDATE")) {
            query.setString(1, studentNo);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("学号不存在。");
                }
            }
        }
    }

    private record TeachingClass(String classNo, String semester, String timeSlot, int enrolledCount, int capacity) {
    }

    private static TeachingClass lockClass(Connection conn, String classNo) throws SQLException {
        String sql = """
                SELECT class_no, semester, time_slot, enrolled_count, capacity
                FROM teaching_class WHERE class_no = ? FOR UPDATE
                """;
        try (PreparedStatement query = conn.prepareStatement(sql)) {
            query.setString(1, classNo);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("教学班号不存在。");
                }
                return new TeachingClass(rows.getString("class_no"), rows.getString("semester"),
                        rows.getString("time_slot"),
                        rows.getInt("enrolled_count"), rows.getInt("capacity"));
            }
        }
    }

    private static void addEnrollment(Connection conn, String studentNo, String classNo, TeachingClass target)
            throws SQLException {
        if (target.enrolledCount() >= target.capacity()) {
            throw new IllegalArgumentException("教学班名额已满。");
        }
        String conflictSql = """
                SELECT e.class_no = ? AS same_class, e.class_no
                FROM enrollment e JOIN teaching_class tc ON tc.class_no = e.class_no
                WHERE e.student_no = ? AND tc.semester = ? AND tc.time_slot = ?
                ORDER BY same_class DESC LIMIT 1
                """;
        try (PreparedStatement query = conn.prepareStatement(conflictSql)) {
            query.setString(1, classNo);
            query.setString(2, studentNo);
            query.setString(3, target.semester());
            query.setString(4, target.timeSlot());
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) {
                    if (rows.getBoolean("same_class")) {
                        throw new IllegalArgumentException("已选过该教学班，不能重复选课。");
                    }
                    throw new IllegalArgumentException("与已选教学班 " + rows.getString("class_no") + " 上课时间冲突。");
                }
            }
        }
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO enrollment (student_no, class_no) VALUES (?, ?)")) {
            insert.setString(1, studentNo);
            insert.setString(2, classNo);
            if (insert.executeUpdate() != 1) {
                throw new SQLException("选课记录写入失败。");
            }
        }
    }

    private static void removeEnrollment(Connection conn, String studentNo, String classNo) throws SQLException {
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT score FROM enrollment WHERE student_no = ? AND class_no = ? FOR UPDATE")) {
            query.setString(1, studentNo);
            query.setString(2, classNo);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("你尚未选修该教学班。");
                }
                if (rows.getBigDecimal("score") != null) {
                    throw new IllegalArgumentException("已录入成绩，不能退课。");
                }
            }
        }
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM enrollment WHERE student_no = ? AND class_no = ?")) {
            delete.setString(1, studentNo);
            delete.setString(2, classNo);
            if (delete.executeUpdate() != 1) {
                throw new SQLException("选课记录删除失败。");
            }
        }
    }
}
