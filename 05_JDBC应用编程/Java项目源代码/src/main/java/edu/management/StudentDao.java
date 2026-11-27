package edu.management;

import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

public final class StudentDao {
    public record Student(String studentNo, String name, String gender, LocalDate birthDate,
            int enrollYear, String className, String majorNo) {
    }

    private final Database db;

    public StudentDao(Database db) {
        this.db = db;
    }

    public int add(Student student) throws SQLException {
        String sql = """
                INSERT INTO student (student_no, name, gender, birth_date, enroll_year, class_name, major_no)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = db.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, student.studentNo());
            statement.setString(2, student.name());
            statement.setString(3, student.gender());
            statement.setObject(4, student.birthDate(), JDBCType.DATE);
            statement.setInt(5, student.enrollYear());
            statement.setString(6, student.className());
            statement.setString(7, student.majorNo());
            return statement.executeUpdate();
        }
    }

    public Optional<Student> find(String studentNo) throws SQLException {
        String sql = """
                SELECT student_no, name, gender, birth_date, enroll_year, class_name, major_no
                FROM student
                WHERE student_no = ?
                """;
        try (Connection connection = db.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentNo);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Student(
                        result.getString("student_no"), result.getString("name"), result.getString("gender"),
                        result.getObject("birth_date", LocalDate.class), result.getInt("enroll_year"),
                        result.getString("class_name"), result.getString("major_no")));
            }
        }
    }

    public boolean updateClass(String studentNo, String className) throws SQLException {
        String sql = "UPDATE student SET class_name = ? WHERE student_no = ?";
        try (Connection connection = db.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, className);
            statement.setString(2, studentNo);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean delete(String studentNo) throws SQLException {
        String sql = "DELETE FROM student WHERE student_no = ?";
        try (Connection connection = db.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentNo);
            return statement.executeUpdate() == 1;
        }
    }
}
