-- 高校教学管理系统：任务四查询集合（MySQL 8.4）
USE edu_ms;

-- Q1 单表条件 + ORDER BY：查询计算机相关专业学生
SELECT student_no, name, enroll_year, class_name
FROM student
WHERE major_no IN ('080901','080902')
ORDER BY student_no;

-- Q2 单表条件 + ORDER BY：查询容量至少40的本学期教学班
SELECT class_no, semester, capacity, enrolled_count
FROM teaching_class
WHERE semester = '2026-1' AND capacity >= 40
ORDER BY capacity DESC, class_no;

-- Q3 INNER JOIN：学生选课、课程和成绩
SELECT s.student_no, s.name, c.course_name, tc.class_no, e.score
FROM enrollment e
INNER JOIN student s ON s.student_no = e.student_no
INNER JOIN teaching_class tc ON tc.class_no = e.class_no
INNER JOIN course c ON c.course_no = tc.course_no
ORDER BY s.student_no, c.course_name;

-- Q4 LEFT JOIN：所有教学班及人数（包括尚无人选课的班）
SELECT tc.class_no, c.course_name, COUNT(e.student_no) AS actual_count,
       tc.enrolled_count AS maintained_count
FROM teaching_class tc
LEFT JOIN course c ON c.course_no = tc.course_no
LEFT JOIN enrollment e ON e.class_no = tc.class_no
GROUP BY tc.class_no, c.course_name, tc.enrolled_count
ORDER BY tc.class_no;

-- Q5 GROUP BY + HAVING：统计至少两名学生选修的课程
SELECT c.course_no, c.course_name, COUNT(e.student_no) AS enrollment_total,
       AVG(e.score) AS average_score
FROM course c
JOIN teaching_class tc ON tc.course_no = c.course_no
JOIN enrollment e ON e.class_no = tc.class_no
GROUP BY c.course_no, c.course_name
HAVING COUNT(e.student_no) >= 2
ORDER BY enrollment_total DESC;

-- Q6 IN 子查询：选修数据库系统课程的学生
SELECT student_no, name
FROM student
WHERE student_no IN (
  SELECT e.student_no
  FROM enrollment e
  JOIN teaching_class tc ON tc.class_no = e.class_no
  WHERE tc.course_no = 'CS000001'
)
ORDER BY student_no;

-- Q7 EXISTS 子查询：至少有一条已选记录的学生
SELECT s.student_no, s.name
FROM student s
WHERE EXISTS (
  SELECT 1 FROM enrollment e WHERE e.student_no = s.student_no
)
ORDER BY s.student_no;

-- Q8 复杂查询：按学期、课程统计班级数、选课人数、平均成绩与及格率
SELECT tc.semester, c.course_no, c.course_name,
       COUNT(DISTINCT tc.class_no) AS class_total,
       COUNT(e.student_no) AS student_total,
       ROUND(AVG(e.score),2) AS average_score,
       ROUND(100.0 * SUM(CASE WHEN e.score >= 60 THEN 1 ELSE 0 END)
             / NULLIF(COUNT(e.score),0),2) AS pass_rate_percent
FROM course c
JOIN teaching_class tc ON tc.course_no = c.course_no
LEFT JOIN enrollment e ON e.class_no = tc.class_no
GROUP BY tc.semester, c.course_no, c.course_name
ORDER BY tc.semester, student_total DESC, c.course_no;

-- 附加完整性核验：结果应为空集，检查冗余人数与明细是否一致
SELECT tc.class_no, tc.enrolled_count, COUNT(e.student_no) AS actual_count
FROM teaching_class tc
LEFT JOIN enrollment e ON e.class_no = tc.class_no
GROUP BY tc.class_no, tc.enrolled_count
HAVING tc.enrolled_count <> COUNT(e.student_no);

-- 附加失败用例（逐条取消注释后单独执行；预期约束拒绝）
-- INSERT INTO enrollment(student_no,class_no) VALUES ('2024000000001','2026-1-CS000001-01'); -- 重复选课主键冲突
-- INSERT INTO enrollment(student_no,class_no,score) VALUES ('2024000000001','2026-1-CS000002-01',120); -- 成绩CHECK失败
-- INSERT INTO course(course_no,course_name,credit,hours,course_type,dept_no) VALUES ('CS999999','非法学分',0.3,16,'选修','CS01'); -- 学分CHECK失败
-- INSERT INTO enrollment(student_no,class_no) VALUES ('2099999999999','2026-1-CS000001-01'); -- 学生外键失败
