-- 高校教学管理系统：MySQL 8.4 LTS
-- 执行方式：mysql -u <user> -p < create_tables.sql
CREATE DATABASE IF NOT EXISTS edu_ms
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE edu_ms;

CREATE TABLE IF NOT EXISTS department (
  dept_no CHAR(4) NOT NULL,
  dept_name VARCHAR(50) NOT NULL,
  phone VARCHAR(20) NULL,
  PRIMARY KEY (dept_no),
  UNIQUE KEY uq_department_name (dept_name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS major (
  major_no CHAR(6) NOT NULL,
  major_name VARCHAR(50) NOT NULL,
  years TINYINT UNSIGNED NOT NULL,
  dept_no CHAR(4) NOT NULL,
  PRIMARY KEY (major_no),
  KEY idx_major_dept (dept_no),
  CONSTRAINT chk_major_years CHECK (years BETWEEN 1 AND 8),
  CONSTRAINT fk_major_department FOREIGN KEY (dept_no)
    REFERENCES department (dept_no) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS student (
  student_no CHAR(13) NOT NULL,
  name VARCHAR(30) NOT NULL,
  gender ENUM('男','女','未说明') NOT NULL,
  birth_date DATE NULL,
  enroll_year SMALLINT UNSIGNED NOT NULL,
  class_name VARCHAR(20) NULL,
  major_no CHAR(6) NOT NULL,
  PRIMARY KEY (student_no),
  KEY idx_student_major (major_no),
  KEY idx_student_year (enroll_year),
  CONSTRAINT chk_student_enroll_year CHECK (enroll_year BETWEEN 2000 AND 2100),
  CONSTRAINT fk_student_major FOREIGN KEY (major_no)
    REFERENCES major (major_no) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS teacher (
  teacher_no CHAR(9) NOT NULL,
  name VARCHAR(30) NOT NULL,
  gender ENUM('男','女','未说明') NOT NULL,
  title ENUM('教授','副教授','讲师','助教','其他') NULL,
  dept_no CHAR(4) NOT NULL,
  PRIMARY KEY (teacher_no),
  KEY idx_teacher_dept (dept_no),
  CONSTRAINT fk_teacher_department FOREIGN KEY (dept_no)
    REFERENCES department (dept_no) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS course (
  course_no CHAR(8) NOT NULL,
  course_name VARCHAR(60) NOT NULL,
  credit DECIMAL(3,1) NOT NULL,
  hours SMALLINT UNSIGNED NOT NULL,
  course_type ENUM('必修','选修','公选') NOT NULL,
  dept_no CHAR(4) NOT NULL,
  PRIMARY KEY (course_no),
  KEY idx_course_dept (dept_no),
  CONSTRAINT chk_course_credit CHECK (
    credit >= 0.5 AND credit <= 10.0 AND MOD(credit * 2, 1) = 0
  ),
  CONSTRAINT chk_course_hours CHECK (hours > 0),
  CONSTRAINT fk_course_department FOREIGN KEY (dept_no)
    REFERENCES department (dept_no) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS teaching_class (
  class_no VARCHAR(24) NOT NULL,
  semester CHAR(6) NOT NULL,
  time_slot VARCHAR(20) NOT NULL,
  classroom VARCHAR(20) NOT NULL,
  capacity SMALLINT UNSIGNED NOT NULL,
  enrolled_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  course_no CHAR(8) NOT NULL,
  teacher_no CHAR(9) NOT NULL,
  PRIMARY KEY (class_no),
  KEY idx_class_course (course_no),
  KEY idx_class_teacher (teacher_no),
  KEY idx_class_semester (semester),
  UNIQUE KEY uq_teacher_schedule (teacher_no, semester, time_slot),
  UNIQUE KEY uq_room_schedule (classroom, semester, time_slot),
  CONSTRAINT chk_class_capacity CHECK (capacity > 0),
  CONSTRAINT chk_class_enrollment_count CHECK (enrolled_count <= capacity),
  CONSTRAINT fk_class_course FOREIGN KEY (course_no)
    REFERENCES course (course_no) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_class_teacher FOREIGN KEY (teacher_no)
    REFERENCES teacher (teacher_no) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS enrollment (
  student_no CHAR(13) NOT NULL,
  class_no VARCHAR(24) NOT NULL,
  select_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  score DECIMAL(5,2) NULL,
  attempt TINYINT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (student_no, class_no),
  KEY idx_enrollment_class (class_no),
  CONSTRAINT chk_enrollment_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
  CONSTRAINT chk_enrollment_attempt CHECK (attempt >= 1),
  CONSTRAINT fk_enrollment_student FOREIGN KEY (student_no)
    REFERENCES student (student_no) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_enrollment_class FOREIGN KEY (class_no)
    REFERENCES teaching_class (class_no) ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB;
