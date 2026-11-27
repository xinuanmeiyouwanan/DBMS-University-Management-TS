package edu.management;

import java.io.BufferedReader;
import java.io.Console;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Main {
    private final BufferedReader input;
    private final Accounts accounts;
    private final GradeBook gradeBook;
    private final StudentDao students;
    private final ReportDao reports;
    private final EnrollmentService enrollment;
    private Accounts.Session session;

    private Main(Database database, BufferedReader input) {
        this.input = input;
        accounts = new Accounts(database);
        gradeBook = new GradeBook(database);
        students = new StudentDao(database);
        reports = new ReportDao(database);
        enrollment = new EnrollmentService(database);
    }

    public static void main(String[] args) {
        boolean provisioning = args.length > 0 && args[0].equals("provision");
        if ((provisioning && (args.length < 4 || args.length > 5)) || (!provisioning && args.length > 1)) {
            System.err.println("用法：Main [配置文件] 或 Main provision 配置文件 ADMIN|STUDENT|TEACHER 登录名 [学号或工号]");
            System.exit(1);
        }
        try {
            Path config = Path.of(provisioning ? args[1] : args.length == 0 ? "db.properties" : args[0]);
            Database database = Database.load(config);
            try (Connection connection = database.open()) {
                System.out.println("已连接数据库 " + connection.getCatalog());
            }
            Console console = System.console();
            BufferedReader input = new BufferedReader(
                    console == null ? new InputStreamReader(System.in) : console.reader());
            Main app = new Main(database, input);
            if (provisioning) {
                app.provision(args);
            } else {
                app.run();
            }
        } catch (EOFException e) {
            System.out.println("\n输入已结束，退出程序。");
            if (provisioning) {
                System.exit(1);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("无法读取配置或输入：" + e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println(databaseError(e));
            System.exit(1);
        }
    }

    private void run() throws IOException {
        System.out.println("高校教学管理系统");
        while (true) {
            try {
                if (session == null) {
                    switch (read("\n1 登录  0 退出")) {
                        case "0" -> {
                            return;
                        }
                        case "1" -> login();
                        default -> System.out.println("请输入菜单中的编号。");
                    }
                } else {
                    switch (session.role()) {
                        case ADMIN -> adminAction();
                        case STUDENT -> studentAction();
                        case TEACHER -> teacherAction();
                    }
                }
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            } catch (SQLException e) {
                System.out.println(databaseError(e));
            }
        }
    }

    private void login() throws SQLException, IOException {
        String login = field("登录名", 64, true);
        char[] supplied = password("密码");
        try {
            session = accounts.authenticate(login, supplied);
            System.out.println("登录成功。");
        } finally {
            Arrays.fill(supplied, '\0');
        }
    }

    private void provision(String[] args) throws SQLException, IOException {
        Accounts.Role role;
        try {
            role = Accounts.Role.valueOf(args[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("账号角色应为 ADMIN、STUDENT 或 TEACHER。");
        }
        char[] first = password("新密码（至少 10 个字符）");
        try {
            char[] second = password("再次输入新密码");
            try {
                if (!Arrays.equals(first, second)) {
                    throw new IllegalArgumentException("两次输入的密码不同。");
                }
                accounts.create(args[3], role, args.length == 5 ? args[4] : null, first);
                System.out.println("账号已开通。");
            } finally {
                Arrays.fill(second, '\0');
            }
        } finally {
            Arrays.fill(first, '\0');
        }
    }

    private char[] password(String prompt) throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] result = console.readPassword("%s%n> ", prompt);
            if (result == null) {
                throw new EOFException();
            }
            return result;
        }
        System.out.print(prompt + "\n> ");
        System.out.flush();
        String line = input.readLine();
        if (line == null) {
            throw new EOFException();
        }
        return line.toCharArray();
    }

    private void adminAction() throws IOException, SQLException {
        String choice = read("\n教务管理\n1 新增学生  2 查询学生  3 修改行政班  4 删除学生  5 教学班人数  0 注销");
        switch (choice) {
            case "1" -> addStudent();
            case "2" -> students.find(studentNo()).ifPresentOrElse(Main::printStudent,
                    () -> System.out.println("查无此学生。"));
            case "3" -> {
                String id = studentNo();
                String newClass = field("新行政班（留空表示未设置）", 20, false);
                System.out.println(students.updateClass(id, newClass) ? "已更新行政班。" : "查无此学生。");
            }
            case "4" -> System.out.println(accounts.deleteStudent(students, studentNo()) ? "已删除学生。" : "查无此学生。");
            case "5" -> showClasses();
            case "0" -> session = null;
            default -> System.out.println("请输入菜单中的编号。");
        }
    }

    private void studentAction() throws IOException, SQLException {
        String id = session.studentNo();
        System.out.printf("%n学生 %s（%s）%n", session.login(), id);
        String choice = read("1 我的成绩单  2 选课  3 退课  4 教学班人数  5 可选教学班  0 注销");
        switch (choice) {
            case "1" -> showTranscript(id);
            case "2" -> {
                enrollment.enroll(id, field("教学班号", 24, true));
                System.out.println("选课成功。");
            }
            case "3" -> {
                enrollment.withdraw(id, field("教学班号", 24, true));
                System.out.println("退课成功。");
            }
            case "4" -> showClasses();
            case "5" -> showAvailableClasses(id);
            case "0" -> session = null;
            default -> System.out.println("请输入菜单中的编号。");
        }
    }

    private void showTranscript(String studentNo) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        Set<String> completedCourses = new HashSet<>();
        BigDecimal earnedCredits = BigDecimal.ZERO.setScale(1);
        for (ReportDao.Grade grade : reports.grades(studentNo)) {
            rows.add(List.of(grade.classNo(), grade.courseName(),
                    grade.score() == null ? "未录入" : grade.score().toPlainString(), grade.credit().toPlainString(),
                    grade.gradePoint() == null ? "—" : grade.gradePoint().toPlainString()));
            if (grade.score() != null && grade.score().compareTo(BigDecimal.valueOf(60)) >= 0
                    && completedCourses.add(grade.courseNo())) {
                earnedCredits = earnedCredits.add(grade.credit());
            }
        }
        if (rows.isEmpty()) {
            System.out.println("暂无选课记录。");
        } else {
            printTable(List.of("教学班号", "课程", "成绩", "学分", "绩点"), rows);
        }
        System.out.println("已修学分合计：" + earnedCredits.toPlainString());
    }

    private void showAvailableClasses(String studentNo) throws IOException, SQLException {
        String course = read("课程名关键词（回车不限）");
        String teacher = read("教师姓名关键词（回车不限）");
        String time = read("上课时段关键词（回车不限）");
        var classes = reports.availableClasses(studentNo, course, teacher, time);
        if (classes.isEmpty()) {
            System.out.println("没有符合条件的可选教学班。");
            return;
        }
        printTable(List.of("教学班号", "学期", "课程", "教师", "上课时段", "教室", "学分", "容量", "余位"),
                classes.stream().map(row -> List.of(row.classNo(), row.semester(), row.courseName(),
                        row.teacherName(), row.timeSlot(), row.classroom(), row.credit().toPlainString(),
                        Integer.toString(row.capacity()), Integer.toString(row.seatsLeft()))).toList());
    }

    private void teacherAction() throws IOException, SQLException {
        String teacherNo = session.teacherNo();
        System.out.printf("%n教师 %s（%s）%n", session.login(), teacherNo);
        String choice = read("1 我的教学班  2 班级名册  3 录入或修改成绩  4 班级成绩统计  5 登记重修成绩  0 注销");
        switch (choice) {
            case "1" -> {
                var classes = gradeBook.classes(teacherNo);
                printTable(List.of("教学班号", "课程", "学期", "成绩状态"), classes.stream()
                        .map(item -> List.of(item.classNo(), item.course(), item.semester(),
                                item.archived() ? "已归档" : "开放"))
                        .toList());
                if (classes.isEmpty()) {
                    System.out.println("暂无授课安排。");
                }
            }
            case "2" -> {
                var roster = gradeBook.roster(teacherNo, field("教学班号", 24, true));
                printTable(List.of("学号", "姓名", "成绩", "修读次数"), roster.stream()
                        .map(entry -> List.of(entry.studentNo(), entry.name(),
                                entry.score() == null ? "未录入" : entry.score().toPlainString(),
                                Integer.toString(entry.attempt())))
                        .toList());
                if (roster.isEmpty()) {
                    System.out.println("暂无选课学生。");
                }
            }
            case "3", "5" -> {
                String classNo = field("教学班号", 24, true);
                String studentNo = studentNo();
                boolean retake = choice.equals("5");
                gradeBook.saveScore(teacherNo, classNo, studentNo, read("成绩（0 至 100，最多两位小数）"), retake);
                System.out.println(retake ? "重修成绩已登记。" : "成绩已保存。");
            }
            case "4" -> showGradeStatistics(teacherNo);
            case "0" -> session = null;
            default -> System.out.println("请输入菜单中的编号。");
        }
    }

    private void showGradeStatistics(String teacherNo) throws SQLException {
        var stats = gradeBook.statistics(teacherNo);
        if (stats.isEmpty()) {
            System.out.println("暂无授课安排。");
            return;
        }
        printTable(List.of("教学班号", "课程", "选课人数", "已出分", "平均分", "最高分", "及格率"), stats.stream()
                .map(row -> List.of(row.classNo(), row.course(), Integer.toString(row.enrolled()),
                        Integer.toString(row.graded()), row.graded() == 0 ? "—" : row.average().toPlainString(),
                        row.graded() == 0 ? "—" : row.highest().toPlainString(),
                        row.graded() == 0 ? "—" : row.passRate().toPlainString() + "%"))
                .toList());
    }

    private void addStudent() throws IOException, SQLException {
        String id = studentNo();
        String name = field("姓名", 30, true);
        String gender = field("性别（男 / 女 / 未说明）", 3, true);
        if (!List.of("男", "女", "未说明").contains(gender)) {
            throw new IllegalArgumentException("性别可填男、女或未说明。");
        }
        String date = read("出生日期（YYYY-MM-DD，可留空）");
        LocalDate birthDate;
        try {
            birthDate = date.isEmpty() ? null : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("出生日期无效，请按 YYYY-MM-DD 输入。");
        }
        int year;
        try {
            year = Integer.parseInt(read("入学年份（2000—2100）"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("入学年份应为 2000—2100 的整数。");
        }
        if (year < 2000 || year > 2100) {
            throw new IllegalArgumentException("入学年份应为 2000—2100 的整数。");
        }
        String className = field("行政班（可留空）", 20, false);
        String majorNo = field("专业号", 6, true);
        int inserted = students.add(new StudentDao.Student(id, name, gender, birthDate, year, className, majorNo));
        System.out.printf("已新增学生。影响行数：%d%n", inserted);
    }

    private void showClasses() throws SQLException {
        List<ReportDao.ClassCount> counts = reports.classCounts();
        if (counts.isEmpty()) {
            System.out.println("暂无教学班。");
            return;
        }
        printTable(List.of("教学班号", "课程", "容量", "已选人数", "实际记录数"), counts.stream()
                .map(row -> List.of(row.classNo(), row.courseName(), Integer.toString(row.capacity()),
                        Integer.toString(row.maintainedCount()), Integer.toString(row.actualCount())))
                .toList());
    }

    private static void printTable(List<String> header, List<List<String>> data) {
        List<List<String>> rows = new ArrayList<>(data.size() + 1);
        rows.add(header);
        rows.addAll(data);
        if (System.console() == null) {
            for (List<String> row : rows) {
                System.out.println(String.join("\t", row));
            }
            return;
        }
        int[] widths = new int[header.size() - 1];
        for (List<String> row : rows) {
            for (int i = 0; i < widths.length; i++) {
                widths[i] = Math.max(widths[i], displayWidth(row.get(i)));
            }
        }
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < widths.length; i++) {
                String cell = row.get(i);
                line.append(cell).append(" ".repeat(widths[i] - displayWidth(cell) + 2));
            }
            System.out.println(line.append(row.get(widths.length)));
        }
    }

    private static int displayWidth(String value) {
        return value.codePoints().map(cp -> {
            int category = Character.getType(cp);
            if (category == Character.NON_SPACING_MARK || category == Character.ENCLOSING_MARK
                    || category == Character.FORMAT) {
                return 0;
            }
            // 表格对齐时，汉字、假名、谚文和全角字符按两列计算。
            if (Character.isIdeographic(cp) || cp >= 0x2E80 && cp <= 0xA4CF
                    || cp >= 0xAC00 && cp <= 0xD7A3 || cp >= 0xFE30 && cp <= 0xFE6F
                    || cp >= 0xFF01 && cp <= 0xFF60 || cp >= 0xFFE0 && cp <= 0xFFE6) {
                return 2;
            }
            return 1;
        }).sum();
    }

    private static void printStudent(StudentDao.Student student) {
        System.out.printf("学号 %s  姓名 %s  性别 %s  出生日期 %s  入学年份 %d  行政班 %s  专业号 %s%n",
                student.studentNo(), student.name(), student.gender(),
                student.birthDate() == null ? "未填写" : student.birthDate(), student.enrollYear(),
                student.className() == null ? "未设置" : student.className(), student.majorNo());
    }

    private String studentNo() throws IOException {
        return field("学号", 13, true);
    }

    private String field(String label, int maxLength, boolean required) throws IOException {
        String value = read(label);
        if (value.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException(label + "不能为空。");
            }
            return null;
        }
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(label + "最多允许 " + maxLength + " 个字符。");
        }
        return value;
    }

    private String read(String prompt) throws IOException {
        System.out.print(prompt + "\n> ");
        System.out.flush();
        String line = input.readLine();
        if (line == null) {
            throw new EOFException();
        }
        return line.strip();
    }

    private static String databaseError(SQLException error) {
        String state = error.getSQLState();
        if (state != null && state.startsWith("08")) {
            return "数据库连接失败，请检查驱动、MySQL 服务和连接配置（SQLState=" + state + "）。";
        }
        return switch (error.getErrorCode()) {
            case 1045 -> "数据库账号或密码错误，请检查连接配置。";
            case 1049, 1146 -> "找不到数据库或数据表，请核对连接配置和建表脚本。";
            case 1062 -> "编号已存在，请检查是否重复添加。";
            case 1217, 1451 -> "该学生已有选课记录或关联账号，无法删除。";
            case 1216, 1452 -> "引用的记录不存在，请核对所填编号。";
            case 1205, 1213 -> "其他操作正在使用相关记录，请重试。";
            default -> "数据库操作失败（SQLState=" + state + "，错误码=" + error.getErrorCode() + "）。";
        };
    }
}
