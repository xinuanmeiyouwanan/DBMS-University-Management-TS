package edu.management;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ReportDao {
    public record Grade(String classNo, String courseNo, String courseName, BigDecimal score,
            BigDecimal credit, BigDecimal gradePoint) {
    }

    public record AvailableClass(String classNo, String semester, String courseName, String teacherName,
            String timeSlot, String classroom, BigDecimal credit, int capacity, int seatsLeft) {
    }

    public record ClassCount(String classNo, String courseName, int capacity, int maintainedCount, int actualCount) {
    }

    private final Database db;

    public ReportDao(Database db) {
        this.db = db;
    }

    public List<Grade> grades(String studentNo) throws SQLException {
        String sql = """
                SELECT tc.class_no, c.course_no, c.course_name, e.score, c.credit,
                       CASE
                           WHEN e.score IS NULL THEN NULL
                           WHEN e.score >= 90 THEN 4.00
                           WHEN e.score >= 85 THEN 3.70
                           WHEN e.score >= 82 THEN 3.30
                           WHEN e.score >= 78 THEN 3.00
                           WHEN e.score >= 75 THEN 2.70
                           WHEN e.score >= 72 THEN 2.30
                           WHEN e.score >= 68 THEN 2.00
                           WHEN e.score >= 64 THEN 1.50
                           WHEN e.score >= 60 THEN 1.00
                           ELSE 0.00
                       END AS grade_point
                FROM student s
                JOIN enrollment e ON e.student_no = s.student_no
                JOIN teaching_class tc ON tc.class_no = e.class_no
                JOIN course c ON c.course_no = tc.course_no
                WHERE s.student_no = ?
                ORDER BY c.course_name, tc.class_no
                """;
        try (Connection connection = db.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentNo);
            try (ResultSet result = statement.executeQuery()) {
                List<Grade> grades = new ArrayList<>();
                while (result.next()) {
                    grades.add(new Grade(result.getString("class_no"), result.getString("course_no"),
                            result.getString("course_name"), result.getBigDecimal("score"),
                            result.getBigDecimal("credit"), result.getBigDecimal("grade_point")));
                }
                return grades;
            }
        }
    }

    public List<AvailableClass> availableClasses(String studentNo, String course, String teacher, String time)
            throws SQLException {
        String sql = """
                SELECT tc.class_no, tc.semester, c.course_name, t.name AS teacher_name, tc.time_slot,
                       tc.classroom, c.credit, tc.capacity, tc.capacity - tc.enrolled_count AS seats_left
                FROM teaching_class tc
                JOIN course c ON c.course_no = tc.course_no
                JOIN teacher t ON t.teacher_no = tc.teacher_no
                WHERE tc.enrolled_count < tc.capacity
                  AND LOCATE(?, c.course_name) > 0 AND LOCATE(?, t.name) > 0 AND LOCATE(?, tc.time_slot) > 0
                  AND NOT EXISTS (
                      SELECT 1 FROM enrollment e JOIN teaching_class chosen ON chosen.class_no = e.class_no
                      WHERE e.student_no = ? AND chosen.semester = tc.semester AND chosen.time_slot = tc.time_slot
                  )
                ORDER BY tc.semester DESC, tc.class_no
                """;
        try (Connection connection = db.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            Set<String> archived = db.archivedClassNumbers(connection);
            statement.setString(1, course);
            statement.setString(2, teacher);
            statement.setString(3, time);
            statement.setString(4, studentNo);
            try (ResultSet rows = statement.executeQuery()) {
                List<AvailableClass> classes = new ArrayList<>();
                while (rows.next()) {
                    String classNo = rows.getString("class_no");
                    if (!archived.contains(classNo)) {
                        classes.add(new AvailableClass(classNo, rows.getString("semester"),
                                rows.getString("course_name"), rows.getString("teacher_name"),
                                rows.getString("time_slot"), rows.getString("classroom"), rows.getBigDecimal("credit"),
                                rows.getInt("capacity"), rows.getInt("seats_left")));
                    }
                }
                return classes;
            }
        }
    }

    public List<ClassCount> classCounts() throws SQLException {
        String sql = """
                SELECT tc.class_no, c.course_name, tc.capacity, tc.enrolled_count,
                       COUNT(e.student_no) AS actual_count
                FROM teaching_class tc
                JOIN course c ON c.course_no = tc.course_no
                LEFT JOIN enrollment e ON e.class_no = tc.class_no
                GROUP BY tc.class_no, c.course_name, tc.capacity, tc.enrolled_count
                ORDER BY tc.class_no
                """;
        try (Connection connection = db.open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            List<ClassCount> classes = new ArrayList<>();
            while (result.next()) {
                classes.add(new ClassCount(
                        result.getString("class_no"), result.getString("course_name"), result.getInt("capacity"),
                        result.getInt("enrolled_count"), result.getInt("actual_count")));
            }
            return classes;
        }
    }
}
