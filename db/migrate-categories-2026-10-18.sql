-- ============================================================
-- 分类改版迁移（2026-10-18）
--
-- 背景：分类从 4 类改成 6 类：
--   旧：AI / Agent、Java 后端、项目实战、随笔
--   新：后端、前端、AI / Agent、开发工具、代码人生、阅读
--
-- 迁移策略（保留旧分类 id，文章自动归位，最少写操作）：
--   1. Java 后端  -> 后端       （原 3 篇不动）
--   2. 随笔       -> 代码人生    （原 9 篇不动）
--   3. AI / Agent -> 不变，只调排序
--   4. 项目实战   -> 11 篇文章并入「后端」，然后删除该分类
--   5. 新增：前端、开发工具、阅读
--
-- 适用：**旧库**（开发库 / 旧服务器库）。
-- 新库（照 2026-10-18 之后的 db/init.sql 建）种子已是 6 类，**不要执行本脚本**。
--
-- 执行方式：
--   mysql -u myRoot -p blog_system < db/migrate-categories-2026-10-18.sql
--   （先备份：mysqldump -u myRoot -p --single-transaction blog_system > backup.sql）
-- ⚠️ 非幂等：只能执行一次；重复执行会因 code 已变化而无效/报主键冲突。
-- ============================================================

START TRANSACTION;

-- 1) 复用旧 id 改名/改 code（文章 category_id 不变，自动落到新分类）
UPDATE categories
SET name = '后端', code = 'backend', description = 'Java、Spring Boot、后端开发相关内容', sort_order = 10
WHERE code = 'java-backend';

UPDATE categories
SET name = '代码人生', code = 'code-life', description = '程序员成长、职业感悟、杂谈', sort_order = 50
WHERE code = 'notes';

UPDATE categories
SET sort_order = 30
WHERE code = 'ai-agent';

-- 2) 「项目实战」的 11 篇文章并入「后端」，然后删除旧分类
UPDATE articles
SET category_id = (SELECT id FROM categories WHERE code = 'backend')
WHERE category_id = (SELECT id FROM categories WHERE code = 'project-practice');

DELETE FROM categories WHERE code = 'project-practice';

-- 3) 新增三个分类。
--    ⚠️ 显式给 id：开发库的 categories.id 是雪花迁移后的值（无 AUTO_INCREMENT），
--    不给 id 会报 1364。取值在现有雪花 id 之后，不冲突。
INSERT INTO categories (id, name, code, description, sort_order, created_at, updated_at) VALUES
    (2085036579597893701, '前端', 'frontend', 'Vue、TypeScript、前端工程化相关内容', 20, NOW(), NOW()),
    (2085036579597893702, '开发工具', 'dev-tools', '开发工具、效率工具、部署与运维', 40, NOW(), NOW()),
    (2085036579597893703, '阅读', 'reading', '读书笔记、技术书籍、文章分享', 60, NOW(), NOW());

-- 4) 自检（执行后看输出）
SELECT id, name, code, sort_order FROM categories ORDER BY sort_order;
SELECT c.code AS category_code, COUNT(a.id) AS article_count
FROM categories c LEFT JOIN articles a ON a.category_id = c.id AND a.deleted_at IS NULL
GROUP BY c.id, c.code ORDER BY c.sort_order;

COMMIT;
