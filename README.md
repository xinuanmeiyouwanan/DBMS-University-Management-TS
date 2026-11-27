# 高校教学管理系统（《数据库系统》课程大作业）

大模型辅助的数据库设计与 JDBC 应用实现。选题为指定选题 1「高校教学管理系统」，覆盖
学生、教师、课程、教学班、选课、成绩与统计的主链路。

## 1. 运行环境

| 项目 | 版本要求 | 本项目使用 |
|---|---|---|
| 数据库 | MySQL 8.4 LTS（8.0 及以上亦可） | MySQL 8.4.9 |
| JDK | Java 17 及以上 | JDK 21.0.12 |
| 构建工具 | Maven 3.9 | Maven 3.9.16 |
| JDBC 驱动 | MySQL Connector/J 9.x | Connector/J 9.7.0（由 `pom.xml` 声明） |
| 字符集 | utf8mb4 / utf8mb4_0900_ai_ci | 同左 |

库名固定为 `edu_ms`，共 7 张表：`department`、`major`、`student`、`teacher`、`course`、
`teaching_class`、`enrollment`。

## 2. 目录结构

```
01_项目需求分析/        需求分析报告（角色、流程、B1—B16 业务规则、AI 修改记录）
02_数据库概念设计/      实体属性表与联系说明、E-R 图（PNG + Mermaid 源码）
03_数据库逻辑设计/      关系模式、函数依赖、1NF/2NF/3NF 推导、无损连接与依赖保持
04_数据库实现/          create_tables.sql、insert_data.sql、query.sql、运行截图、运行记录
05_JDBC应用编程/        Java 源码（Maven 工程）、程序运行截图
06_测试与优化/          功能测试报告（29 条用例）
07_AI辅助过程/          Prompt 与交互记录、AI 辅助效果评价
```

## 3. 数据库创建步骤

**① 建库建表**（在 `04_数据库实现/` 目录下执行；`create_tables.sql` 可重复执行，会自动建库）

```powershell
# cmd
mysql -u root -p < create_tables.sql
# PowerShell（不支持 < 重定向）
Get-Content .\create_tables.sql -Raw -Encoding utf8 | mysql -u root -p --default-character-set=utf8mb4
```

**② 导入样例数据**（每个教学班至少 1 名学生，共 13 条选课记录；结尾会按明细重算已选人数）

```powershell
Get-Content .\insert_data.sql -Raw -Encoding utf8 | mysql -u root -p --default-character-set=utf8mb4
```

**③ 验证**：`SHOW TABLES;` 应有 7 张表；`SELECT COUNT(*) FROM enrollment;` 应为 13。
`query.sql` 末尾附有冗余计数一致性核验，结果应为空集。

## 4. 运行程序

详细步骤见 [`05_JDBC应用编程/README.md`](05_JDBC应用编程/README.md)，要点如下。

```powershell
cd 05_JDBC应用编程/Java项目源代码
Copy-Item ./db.properties.example ./db.properties   # 填写 user / password，首次配置
mvn -q compile exec:java                              # 启动
```

- 程序采用 `util.Database` + `dao` + `service` + `main` 分层，全部使用 `PreparedStatement`。
- **选退课演示需要在 `db.properties` 配置 `selection.start` / `selection.end`**（北京时间，
  如 `2026-11-01T08:00`），窗口外选课会被拒绝；`grade.archived.classes` 用于模拟成绩归档。
- 首次运行需先为演示账号执行 `provision` 子命令，否则无法登录（命令见 05 目录的 README）。
- `db.properties` 与应用账号文件均为本机配置，不纳入版本库。

## 5. 分工

三名成员按「设计线—数据线—程序线」划分，共 35 / 30 / 35 分，详见 [小组分工说明](../小组分工说明_高校教学管理系统.docx)。

## 6. 提交说明

源码、SQL 脚本、测试报告与 AI 交互记录均已按上述目录组织；`07_AI辅助过程/` 中的记录按日期追加，
每条标注对应到具体设计或代码成果。
