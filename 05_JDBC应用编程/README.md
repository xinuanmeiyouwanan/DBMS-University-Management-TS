# 高校教学管理系统 · JDBC 应用

命令行程序使用 JDK 21、Maven 3.9 和 MySQL 8.x。任务四的建库脚本面向 MySQL 8.4 LTS，项目通过 Maven 加载 MySQL Connector/J 9.7.0。

## 1. 初始化数据库

在 MySQL 中用管理员账号依次执行[建表脚本](../04_数据库实现/create_tables.sql)和[样例数据脚本](../04_数据库实现/insert_data.sql)。前者创建 `edu_ms` 和七张表，后者写入演示数据，只在首次初始化时执行。

若本机尚无应用专用的 MySQL 用户，用管理员账号执行以下 SQL。第一条语句返回随机密码，下一步填写连接配置时使用。

```sql
CREATE USER 'edu_app'@'localhost' IDENTIFIED BY RANDOM PASSWORD;
GRANT SELECT, INSERT, UPDATE, DELETE ON edu_ms.student TO 'edu_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON edu_ms.enrollment TO 'edu_app'@'localhost';
GRANT SELECT, UPDATE ON edu_ms.teaching_class TO 'edu_app'@'localhost';
GRANT SELECT ON edu_ms.teacher TO 'edu_app'@'localhost';
GRANT SELECT ON edu_ms.course TO 'edu_app'@'localhost';
```

## 2. 配置 JDBC

从仓库根目录打开 PowerShell，首次配置时执行：

```powershell
Set-Location './05_JDBC应用编程/Java项目源代码'
Copy-Item './db.properties.example' './db.properties'
```

在 `db.properties` 中填写 MySQL 的 `user` 和 `password`。模板默认通过 TLS 连接 `127.0.0.1:3306/edu_ms`，数据库在其他地址时修改 `url`。

要演示选退课，请设置 `selection.start` 和 `selection.end`，使用北京时间，例如 `2026-11-27T08:00`。窗口从开始时刻生效，到结束时刻关闭。两项留空时关闭。教务在 `grade.archived.classes` 中用英文逗号列出已归档的教学班号。班号按数据库的排序规则匹配，例如样例班号中的 `CS` 也可写成 `cs`。修改配置后重启程序。

## 3. 开通应用账号并启动

在同一目录中运行以下命令。每个账号开通一次，按提示设置至少 10 个字符的应用密码。示例中的学号和工号来自任务四的样例数据。

```powershell
mvn -q compile exec:java '-Dexec.args=provision db.properties ADMIN registrar'
mvn -q compile exec:java '-Dexec.args=provision db.properties STUDENT chenlin 2024000000004'
mvn -q compile exec:java '-Dexec.args=provision db.properties STUDENT zhangchen 2024000000001'
mvn -q compile exec:java '-Dexec.args=provision db.properties TEACHER gaoming T20200001'
mvn -q compile exec:java
```

看到“已连接数据库 edu_ms”后，选择“1 登录”，输入刚开通的应用账号。以后启动只需运行最后一条命令。

应用账号默认保存在 `.work/accounts.properties`。

## 4. 用任务四的数据演示

| 登录账号 | 菜单操作 | 核对结果 |
|---|---|---|
| `registrar`（教务） | 新增学生，使用新学号和已有专业号 `080901`。按学号查询、修改行政班、再次查询，最后删除并查询 | 修改后显示新班级，删除后显示“查无此学生” |
| `chenlin`（陈琳） | 查“可选教学班”，依次输入 `程序`、`林芳`、`周一` | 命中林芳的“程序设计基础”，容量 40，余位 38。三个条件同时匹配，可按字面片段搜索，留空表示不限 |
| `chenlin`（陈琳） | 在选课窗口内选择“程序设计基础”教学班 `2026-1-CS000002-01`，再选一次，最后退课 | 已选人数从 2 变为 3，重复申请时仍为 3，退课后回到 2。人数菜单的“实际记录数”同步变化 |
| `zhangchen`（张晨） | 查“我的成绩单” | 数据库系统 92.50 分、3.0 学分、绩点 4.00。程序设计基础 88.00 分、4.0 学分、绩点 3.70。已修学分合计 7.0 |
| `gaoming`（高明） | 从“班级名册”查询 `2026-1-CS000001-01`，再选“班级成绩统计” | “数据库系统”班有 3 名学生。张晨 92.50 分、李悦 85.00 分，王浩未录入。两份已录成绩的平均分为 88.75 |

“可选教学班”显示有余位、未归档且与本人已有课表无冲突的班级。窗口关闭时仍可预览，选课与退课须在窗口内提交。本人已选的班级可在成绩单中查看。

成绩单按原始百分制分数分档，小数成绩直接比较。例如 84.99 分对应 3.30，85.00 分对应 3.70。

| 百分制分数 | 绩点 |
|---|---:|
| 90 ≤ 分数 ≤ 100 | 4.00 |
| 85 ≤ 分数 < 90 | 3.70 |
| 82 ≤ 分数 < 85 | 3.30 |
| 78 ≤ 分数 < 82 | 3.00 |
| 75 ≤ 分数 < 78 | 2.70 |
| 72 ≤ 分数 < 75 | 2.30 |
| 68 ≤ 分数 < 72 | 2.00 |
| 64 ≤ 分数 < 68 | 1.50 |
| 60 ≤ 分数 < 64 | 1.00 |
| 分数 < 60 | 0.00 |
| 未录入 | — |

已修学分只累计及格课程，同一课程号只计一次。

教师在菜单 3 录入或更正成绩，修读次数保持原值。已有成绩时，可在菜单 5 登记重修，以新成绩覆盖该学生在该班的原成绩，修读次数加 1。例如为王浩首次录入 55 分，再登记重修 78 分，次数从 1 变为 2。把 78 分更正为 79 分后，次数仍为 2。已归档的班级会拒绝这两种操作。

运行图 02、03、04、06、07 节选自本次复测的命令行输入与输出。图 06 同时列出两个独立数据库连接的查询结果。图 00、01、05 沿用此前的运行截图。[程序运行截图](程序运行截图) · [任务 6.1 功能测试报告](../06_测试与优化/功能测试报告.pdf)
