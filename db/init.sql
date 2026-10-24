-- ============================================
-- Blog System 数据库初始化脚本
-- 使用方式：mysql -u myRoot -p < db/init.sql
-- 最后更新：2026-10-09
--
-- 【2026-10-09 索引对齐】本文件的索引定义此前与开发库（真实在跑的那份）严重漂移：
-- 实库经过一轮索引治理（单列升复合、删冗余、补唯一约束），但没有回写到这里。
-- 后果是「照本文件新建的库」比开发库少一批复合索引——实测首页「最新」排序
-- （ORDER BY published_at DESC, id DESC）会从索引扫描退化成 Using filesort。
-- 本次按实库逐条对齐，验证方式：新建临时库执行本文件，再用 information_schema
-- 与开发库逐条比对索引（28 表 / 89 索引）。
--
-- 仍存在的差异只有索引「命名」（例如 username 的内联 UNIQUE 未命名 vs 实库
-- uk_users_username）。唯一约束的有无是行为差异、名字不是，故未改动。
--
-- ⚠️ 已知反向缺口：开发库里**缺** idx_ai_messages_agent_run_id(agent_run_id)，
-- 而本文件第 362 行建了它、代码也在等值查这一列（TaskWorkflowContinuationService）。
-- 按项目约定「改了本文件要同步在本地库执行 DDL」，这条属于当年漏执行——
-- 补建请手工在开发库执行，本文件不用改。
--
-- 【2026-10-09 游标分页：新增两条排序索引】首页从 offset 改成游标分页（keyset）时加的，
-- 同时删掉了被它们完全覆盖的 idx_articles_status_published_at。
-- 本地实库 EXPLAIN 的效果：recommend 从 `Using filesort` 变成 `Backward index scan`，
-- 两条排序的 key_len 都从「只有 status」变成「status + deleted_at 两个等值列」。
-- 已经在本机开发库执行过；**新环境照本文件建库即可，老库要手工补**：
--   ALTER TABLE articles ADD INDEX idx_articles_status_deleted_published (status, deleted_at, published_at, id);
--   ALTER TABLE articles ADD INDEX idx_articles_status_deleted_view (status, deleted_at, view_count, id);
--   ALTER TABLE articles DROP INDEX idx_articles_status_published_at;
-- ============================================

-- ============================================
-- ⚠️ 2026-10-18：必须在脚本里显式声明字符集。
-- 用法（Linux/Windows 客户端都建议带上字符集参数）：
--   mysql -u myRoot -p --default-character-set=utf8mb4 < db/init.sql
-- 少了它，客户端默认按平台编码（Windows GBK）发送脚本字节，
-- 分类/标签等中文种子数据会以乱码入库（不影响 ASCII，所以很隐蔽）。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS blog_system
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE blog_system;

-- ============================================
-- 1. 用户表
-- ============================================
CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    username VARCHAR(50) NOT NULL COMMENT '用户名（登录用）',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希（password 登录方式）',
    nickname VARCHAR(50) NOT NULL COMMENT '展示昵称',
    avatar_url VARCHAR(500) DEFAULT NULL COMMENT '头像 URL',
    bio VARCHAR(500) DEFAULT NULL COMMENT '个人简介',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    followers_count INT NOT NULL DEFAULT 0 COMMENT '关注者数量',
    following_count INT NOT NULL DEFAULT 0 COMMENT '关注的数量',
    login_type VARCHAR(20) NOT NULL DEFAULT 'password' COMMENT '登录方式：password / github',
    github_id BIGINT DEFAULT NULL COMMENT 'GitHub 用户 ID',
    token_version INT NOT NULL DEFAULT 0 COMMENT 'token 代次：改密/重置后 +1，令该用户已签发的全部 token 立即失效（JWT 无状态，黑名单只能按 jti 拉黑单个，批量作废只能靠它）。老库手工补：ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0;',
    email VARCHAR(255) DEFAULT NULL COMMENT '邮箱：找回密码的唯一通道。**老数据为 NULL**（唯一索引允许多个 NULL，所以不冲突），这类账号走不了找回流程。老库手工补：ALTER TABLE users ADD COLUMN email VARCHAR(255) DEFAULT NULL, ADD UNIQUE KEY uk_users_email (email);',
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY idx_github_id (github_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================
-- 2. 用户关注关系表
-- ============================================
CREATE TABLE IF NOT EXISTS user_follows (
    id BIGINT NOT NULL PRIMARY KEY,
    follower_id BIGINT NOT NULL COMMENT '关注者用户 ID',
    following_id BIGINT NOT NULL COMMENT '被关注者用户 ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '首次关注时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近操作时间',
    deleted_at DATETIME DEFAULT NULL COMMENT '取关时间（软删除）',
    UNIQUE KEY uk_follow (follower_id, following_id),
    INDEX idx_follower (follower_id, deleted_at),
    INDEX idx_following (following_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注关系表';

-- ============================================
-- 3. 分类表
-- ============================================
CREATE TABLE IF NOT EXISTS categories (
    -- 分类是固定清单（V1 起全站共用，不开放用户创建），id 交给数据库生成即可；
    -- 下面的种子 INSERT 不写 id，没有 AUTO_INCREMENT 会直接报 1364 建不出数据。
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL COMMENT '分类名称',
    code VARCHAR(50) NOT NULL COMMENT '分类编码（URL 友好）',
    description VARCHAR(255) DEFAULT NULL COMMENT '分类描述',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序序号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_categories_name (name),
    UNIQUE KEY uk_categories_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章分类表';

-- ============================================
-- 4. 标签表
-- ============================================
CREATE TABLE IF NOT EXISTS tags (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    name VARCHAR(50) NOT NULL COMMENT '标签名',
    created_by BIGINT UNSIGNED DEFAULT NULL COMMENT '创建者用户 ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tags_created_by (created_by),
    UNIQUE KEY uk_tags_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章标签表';

-- ============================================
-- 5. 文章表
-- ============================================
CREATE TABLE IF NOT EXISTS articles (
    -- ⚠️ 必须 AUTO_INCREMENT：Articles 实体是 @TableId(IdType.AUTO)，全局 id-type 也是 auto，
    -- 插入时 MyBatis-Plus 不提供 id，交给 DB 生成。少了这个子句，新库插入文章会直接报
    -- Field 'id' doesn't have a default value
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    author_id BIGINT UNSIGNED NOT NULL COMMENT '作者用户 ID',
    category_id BIGINT UNSIGNED DEFAULT NULL COMMENT '分类 ID',
    title VARCHAR(120) NOT NULL COMMENT '文章标题',
    summary VARCHAR(300) DEFAULT NULL COMMENT '文章摘要',
    content LONGTEXT NOT NULL COMMENT '文章正文（Markdown）',
    cover_url VARCHAR(500) DEFAULT NULL COMMENT '封面图片 URL',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=草稿 1=已发布 2=已隐藏',
    view_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '浏览量',
    like_count INT DEFAULT 0 COMMENT '点赞数',
    favorite_count INT DEFAULT 0 COMMENT '收藏数',
    comment_count INT DEFAULT 0 COMMENT '评论数',
    share_count INT DEFAULT 0 COMMENT '分享数',
    published_at DATETIME DEFAULT NULL COMMENT '发布时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL COMMENT '软删除时间',
    INDEX idx_articles_author_id (author_id),
    INDEX idx_articles_category_id (category_id),
    INDEX idx_articles_deleted_at (deleted_at),
    -- 首页两条排序各配一条复合索引。三处讲究：
    --   ① 含 deleted_at —— 软删除条件不在索引里的话，扫出来的每一行都要回表判断，等于白扫；
    --   ② 顺序是「等值列 → 排序列 → tiebreaker」；
    --   ③ 带 id 是为了让 `ORDER BY x DESC, id DESC` 能整个从索引里读出来（游标分页的全序靠它）。
    -- latest    → WHERE status=? AND deleted_at IS NULL ORDER BY published_at DESC, id DESC
    -- recommend → WHERE status=? AND deleted_at IS NULL ORDER BY view_count DESC,   id DESC
    -- 旧的单列 idx_status 已被这两条覆盖，删掉了（留着只是白白增加写放大）
    INDEX idx_articles_status_deleted_published (status, deleted_at, published_at, id),
    INDEX idx_articles_status_deleted_view (status, deleted_at, view_count, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章表';

-- ============================================
-- 6. 文章-标签关联表
-- ============================================
CREATE TABLE IF NOT EXISTS article_tags (
    article_id BIGINT UNSIGNED NOT NULL,
    tag_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (article_id, tag_id),
    -- 按标签反查文章。主键最左前缀是 article_id，反方向查用不上
    INDEX idx_article_tags_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章与标签多对多关联表';

-- ============================================
-- 7. 文章点赞表
-- ============================================
CREATE TABLE IF NOT EXISTS article_likes (
    id BIGINT NOT NULL PRIMARY KEY,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    -- 不再单建 (article_id)：uk_article_user 的最左前缀就是它，单列索引是纯冗余（只增写开销）
    UNIQUE KEY uk_article_user (article_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章点赞表';

-- ============================================
-- 8. 文章收藏表
-- ============================================
CREATE TABLE IF NOT EXISTS article_favorites (
    id BIGINT NOT NULL PRIMARY KEY,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    -- 同 article_likes：uk_article_user 的最左前缀已覆盖 (article_id)
    UNIQUE KEY uk_article_user (article_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章收藏表';

-- ============================================
-- 9. 文章评论表
-- ============================================
CREATE TABLE IF NOT EXISTS article_comments (
    id BIGINT NOT NULL PRIMARY KEY,
    article_id BIGINT NOT NULL COMMENT '所属文章 ID',
    user_id BIGINT NOT NULL COMMENT '评论者用户 ID',
    content VARCHAR(2000) NOT NULL COMMENT '评论内容',
    root_id BIGINT DEFAULT NULL COMMENT '根评论 ID（顶级评论为 NULL，多级回复时指向顶层）',
    parent_id BIGINT DEFAULT NULL COMMENT '直接父评论 ID（回复谁）',
    ip VARCHAR(45) DEFAULT NULL COMMENT '评论者 IP',
    ip_location VARCHAR(100) DEFAULT NULL COMMENT 'IP 属地',
    like_count INT NOT NULL DEFAULT 0 COMMENT '点赞数',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL DEFAULT NULL COMMENT '软删除时间',
    deleted_by BIGINT DEFAULT NULL COMMENT '删除操作人 ID',
    -- 评论列表按「文章 + 根评论 + 时间」取，单列 article_id 覆盖不到排序
    INDEX idx_article_root_time (article_id, root_id, created_at),
    INDEX idx_user_id (user_id),
    INDEX idx_parent_id (parent_id),
    INDEX idx_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章评论表（支持多级回复）';

-- ============================================
-- 10. 评论点赞表
-- ============================================
CREATE TABLE IF NOT EXISTS comment_likes (
    id BIGINT NOT NULL PRIMARY KEY,
    comment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- 一人对一条评论只能点一次赞：原先只有普通索引，重复点赞全靠应用层挡
    -- 不单建 (comment_id)：uk_comment_user 的最左前缀已经覆盖它
    UNIQUE KEY uk_comment_user (comment_id, user_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论点赞表';

-- ============================================
-- 11. AI 会话表
-- ============================================
CREATE TABLE IF NOT EXISTS ai_sessions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '所属用户 ID',
    title VARCHAR(100) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    active_workflow_run_id BIGINT NULL COMMENT '当前会话正在进行的 Workflow Run ID',
    last_article_id BIGINT NULL COMMENT '会话文章锚：最后一次真实进入文章上下文的文章（V3.8，本体）',
    last_article_title VARCHAR(255) NULL COMMENT '锚标题快照（展示/候选注入参考；权威标题读时查库现取，允许过期）',
    last_article_source VARCHAR(32) NULL COMMENT '锚写点：AGENT_RUN / PAGE_QA / PAGE_ACTION',
    last_article_updated_at DATETIME NULL COMMENT '锚写入时刻',
    last_conclusion_article_id BIGINT NULL COMMENT '结论锚：结论针对的文章（V3.12）',
    last_conclusion_text VARCHAR(2000) NULL COMMENT '结论锚：用户看到的结论正文（finalAnswer / suggestion.reason）',
    last_conclusion_source_run_id BIGINT NULL COMMENT '结论锚：来源 Agent run（审计 + 并发 CAS 依据）',
    last_conclusion_source_type VARCHAR(32) NULL COMMENT '结论锚：来源终态类型（ARTICLE_AGENT_FINAL_ANSWER / WORKFLOW_SUGGESTION_CONFIRMED，审计+评测标签）',
    last_conclusion_updated_at DATETIME NULL COMMENT '结论锚写入时刻（仅审计，不参与判断）',
    -- V4.x 会话学习计划锚。**2026-10-18 补**：这四列当时只改了实体 / 测试 schema / 本地开发库，
    -- 漏了本文件 —— 于是「照本文件建的新库」第一次写锚就报 Unknown column 'last_learning_plan_id'
    -- （与 AGENTS.md 记的 2026-09-30 parent_run_id 同款，但方向相反：那次缺的是开发库，这次缺的是建库脚本）。
    -- 2026-10-09 的全库审计没抓到它：那次比对口径是**索引**（28 表 / 89 索引），
    -- 而这是**纯列变更**（没建索引），正好从口径里漏过去。已把「列」加进比对项。
    -- 写在这里而不是走文件末尾的 ALTER 追加：本文件的首要用途是「照它建出新库」，
    -- 内联才能让新库一次到位；老库早已由本地手工 ALTER 补齐，CREATE TABLE IF NOT EXISTS 会跳过本语句。
    -- 老库若确实缺列，手工补：
    --   ALTER TABLE ai_sessions
    --     ADD COLUMN last_learning_plan_id BIGINT NULL,
    --     ADD COLUMN last_learning_plan_title VARCHAR(255) NULL,
    --     ADD COLUMN last_learning_plan_source VARCHAR(32) NULL,
    --     ADD COLUMN last_learning_plan_updated_at DATETIME NULL;
    last_learning_plan_id BIGINT NULL COMMENT '会话学习计划锚：最后一次"真实定位到"的学习计划（本体）',
    last_learning_plan_title VARCHAR(255) NULL COMMENT '锚标题快照（候选注入参考；权威标题读时查库现取，允许过期）',
    last_learning_plan_source VARCHAR(32) NULL COMMENT '锚写点：CLASSIFIER / BACKEND_MATCH / USER_SELECTION',
    last_learning_plan_updated_at DATETIME NULL COMMENT '锚写入时刻',
    -- 2026-10-19：最近一次文章检索（RAG）的**有序候选**，服务「详细总结第一篇 / 第二篇」这类序位指代。
    -- 单槽 last_article_id 只能记住一篇，序位指代结构性无解（见 ArticleQaTargetResolver#resolvePositional）。
    -- 只存 articleId + 标题快照，最多 5 条；清空对话时与 V3.8 文章锚一并清（同一语义）。
    -- 老库手工补：ALTER TABLE ai_sessions ADD COLUMN last_rag_articles_json TEXT NULL;
    last_rag_articles_json TEXT NULL COMMENT '会话最近一次文章检索的有序候选（JSON：articleId + title，序位指代用）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手会话表';

-- ============================================
-- 12. AI 消息表
-- ============================================
CREATE TABLE IF NOT EXISTS ai_messages (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL COMMENT '所属会话 ID',
    role VARCHAR(20) NOT NULL COMMENT '消息角色：user / ai',
    content TEXT NOT NULL COMMENT '消息内容',
    page_context JSON DEFAULT NULL COMMENT '发送消息时的页面上下文',
    token_count INT DEFAULT 0 COMMENT 'token 消耗量',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_created (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 对话消息表';

-- ============================================
-- 13. AI 用户记忆表
-- ============================================

CREATE TABLE ai_user_memories (
    id BIGINT PRIMARY KEY,

    user_id BIGINT NOT NULL,

    memory_type VARCHAR(32) NOT NULL COMMENT '记忆类型：PROFILE / PREFERENCE / PROJECT_STATE',
    memory_key VARCHAR(64) NOT NULL COMMENT '记忆键，用于同类记忆去重和更新',
    content TEXT NOT NULL COMMENT '自然语言记忆内容',

    source VARCHAR(32) NOT NULL COMMENT '来源：USER_EXPLICIT / USER_CONFIRMED / SYSTEM_SUMMARY',
    confidence DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '可信度，0-1',
    importance TINYINT NOT NULL DEFAULT 5 COMMENT '重要性，1-10',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1启用，0停用',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_type_key (user_id, memory_type, memory_key),
    -- 不单建 (user_id, enabled)：idx_user_importance 的最左前缀已经覆盖它
    KEY idx_user_type_enabled (user_id, memory_type, enabled),
    KEY idx_user_importance (user_id, enabled, importance)
);

-- ============================================
-- 14. AI 用户记忆候选表
-- ============================================

CREATE TABLE IF NOT EXISTS ai_user_memory_candidates (
    id BIGINT PRIMARY KEY,

    user_id BIGINT NOT NULL,
    session_id BIGINT DEFAULT NULL,
    message_id BIGINT DEFAULT NULL,

    memory_type VARCHAR(32) NOT NULL COMMENT '候选记忆类型：PROFILE / PREFERENCE / PROJECT_STATE',
    memory_key VARCHAR(64) NOT NULL COMMENT '候选记忆键',
    content TEXT NOT NULL COMMENT 'AI 提取出的候选记忆内容',

    candidate_action VARCHAR(32) NOT NULL COMMENT '候选动作：CREATE / UPDATE / MERGE / IGNORE',
    reason VARCHAR(500) DEFAULT NULL COMMENT 'AI 提取该候选记忆的原因',
    decision_reason VARCHAR(500) DEFAULT NULL COMMENT 'AI 判断候选记忆更新动作的原因',
    merged_content TEXT COMMENT 'MERGE 动作对应的合并后内容',
    source VARCHAR(32) NOT NULL DEFAULT 'AI_EXTRACTED' COMMENT '来源：AI_EXTRACTED',

    confidence DECIMAL(3,2) NOT NULL DEFAULT 0.80 COMMENT '可信度，0-1',
    importance TINYINT NOT NULL DEFAULT 5 COMMENT '重要性，1-10',

    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / CONFIRMED / REJECTED',
    target_memory_id BIGINT DEFAULT NULL COMMENT 'UPDATE 候选对应的正式记忆 ID',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    decided_at DATETIME DEFAULT NULL COMMENT '确认或拒绝时间',

    KEY idx_memory_candidate_user_status (user_id, status),
    KEY idx_memory_candidate_user_type_key (user_id, memory_type, memory_key),
    KEY idx_memory_candidate_target_memory_id (target_memory_id),
    KEY idx_memory_candidate_status_created_at (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 用户记忆候选表';


-- ============================================
-- 14.1 AI 记忆事件消费幂等表（RocketMQ）
-- ============================================
-- RocketMQ 是 at-least-once：消费端按 eventId 抢占，处理成功后才标记 SUCCEEDED；
-- 处理失败时整笔事务回滚（含本行），消息重投可以再次进入。
-- 生产端 eventId 由业务消息 ID 派生（见 MemoryExtractionEvent），重试不会产生新事件。
-- ⚠️ 应用启动时 JdbcMemoryExtractionIdempotencyStore 也会 CREATE TABLE IF NOT EXISTS 兜底；
--    这里补进 init.sql 是为了「照脚本新建的库」与开发库结构一致（项目 DDL 同步约定）。

CREATE TABLE IF NOT EXISTS ai_memory_extraction_events (
    event_id VARCHAR(128) NOT NULL COMMENT '事件 ID：memory:candidate:<userMessageId> / memory:episodic:<assistantMessageId> / memory:summary:<assistantMessageId>',
    event_type VARCHAR(32) NOT NULL COMMENT 'CANDIDATE / EPISODIC / SESSION_SUMMARY',
    status VARCHAR(32) NOT NULL COMMENT 'PROCESSING / SUCCEEDED',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 记忆事件消费幂等表（RocketMQ at-least-once）';


-- ============================================
-- 15. AI 工作流表
-- ============================================

CREATE TABLE ai_workflow_runs (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NULL,
  workflow_type VARCHAR(64) NOT NULL,
  workflow_version VARCHAR(32) NOT NULL DEFAULT '1.0',
  status VARCHAR(64) NOT NULL,
  current_step VARCHAR(64) NULL,
  context_json JSON NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  input_tokens INT NOT NULL DEFAULT 0,
  output_tokens INT NOT NULL DEFAULT 0,
  total_tokens INT NOT NULL DEFAULT 0,
  pause_reason VARCHAR(255) NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_user_status (user_id, status),
  INDEX idx_conversation_id (conversation_id),
  -- 启动恢复 / 巡检按 status 找 RUNNING 的 workflow（此前全表扫）
  INDEX idx_workflow_run_status (status, id)
);

-- ============================================
-- 16. AI Workflow步骤执行日志表
-- ============================================

CREATE TABLE IF NOT EXISTS ai_workflow_step_logs (
    id BIGINT PRIMARY KEY,

    workflow_run_id BIGINT NOT NULL COMMENT '对应 ai_workflow_runs.id',

    log_type VARCHAR(20) NOT NULL DEFAULT 'OPERATION' COMMENT '日志类型：OPERATION=操作级 / STEP=步骤级',

    step_order INT NOT NULL COMMENT '步骤顺序',

    step VARCHAR(64) NOT NULL COMMENT '步骤名称，例如 REQUIREMENT_ANALYZE/RAG_SEARCH/GENERATE_DRAFT',

    status VARCHAR(32) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED',

    retry_count INT NOT NULL DEFAULT 0 COMMENT '当前步骤重试次数，首次执行为0',

    input_summary TEXT COMMENT '输入摘要',

    output_summary TEXT COMMENT '输出摘要',

    error_message VARCHAR(1000) COMMENT '错误信息',

    metadata_json JSON COMMENT '扩展信息，例如模型、参数、工具信息',

    started_at DATETIME NOT NULL COMMENT '开始时间',

    ended_at DATETIME COMMENT '结束时间',

    duration_ms BIGINT COMMENT '耗时毫秒',

    input_tokens INT NOT NULL DEFAULT 0 COMMENT '输入token',

    output_tokens INT NOT NULL DEFAULT 0 COMMENT '输出token',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_workflow_timeline(workflow_run_id, step_order, id),

    KEY idx_workflow_step_retry(workflow_run_id, step, retry_count),

    KEY idx_workflow_status(status)
)
COMMENT='AI Workflow步骤执行日志';

ALTER TABLE ai_messages
ADD COLUMN workflow_run_id BIGINT NULL COMMENT '关联 ai_workflow_runs.id'
AFTER session_id;

CREATE INDEX idx_ai_messages_workflow_run_id
ON ai_messages(workflow_run_id);

ALTER TABLE ai_messages
ADD COLUMN agent_run_id BIGINT NULL COMMENT '关联 ai_agent_runs.id'
AFTER workflow_run_id;

CREATE INDEX idx_ai_messages_agent_run_id
ON ai_messages(agent_run_id);

-- ============================================
-- 17.学习计划：LearningPlanWorkflow 的业务对象表
-- ============================================
CREATE TABLE IF NOT EXISTS learning_plans (
  id BIGINT NOT NULL COMMENT '主键（雪花）',
  user_id BIGINT NOT NULL COMMENT '所属用户',
  title VARCHAR(200) NOT NULL COMMENT '计划标题',
  goal VARCHAR(500) NULL COMMENT '学习目标描述',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/COMPLETED/ARCHIVED',
  source_workflow_run_id BIGINT NULL COMMENT '创建它的 workflow run id（幂等唯一键）',
  -- 2026-10-18 补：乐观锁 version（AGENTS.md「MySQL 乐观锁（双表 version）」里的计划表那一半）。
  -- 实体 LearningPlans:30 有该字段、测试 schema.sql:278 也有，唯独本文件漏了 —— 比会话锚那次更严重：
  -- MyBatis-Plus 的 selectById 会**显式列出全部字段**，照本文件建的新库上学习计划第一次查询就报
  -- Unknown column 'version'（不是写才失败，是读就失败）。老库已有该列，无需处理；
  -- 若老库确实缺，手工补：ALTER TABLE learning_plans ADD COLUMN version INT NOT NULL DEFAULT 0;
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_learning_plans_run_id (source_workflow_run_id),
  KEY idx_learning_plans_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习计划（Workflow 生成，用户长期拥有）';


-- ============================================
-- 18.AI 学习规划任务表
-- ============================================

CREATE TABLE IF NOT EXISTS learning_stages (
  id BIGINT NOT NULL COMMENT '主键（雪花）',
  plan_id BIGINT NOT NULL COMMENT '所属计划 learning_plans.id',
  order_num INT NOT NULL DEFAULT 0 COMMENT '阶段顺序',
  title VARCHAR(200) NOT NULL COMMENT '阶段标题',
  tasks JSON NULL COMMENT '任务列表：[{"title":"...","done":false}]',
  -- 2026-10-18 补：双表 version 的第二张（同上，实体 LearningStages + 测试 schema.sql:290 都有）。
  -- 老库手工补：ALTER TABLE learning_stages ADD COLUMN version INT NOT NULL DEFAULT 0;
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_learning_stages_plan (plan_id, order_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学习阶段（任务内嵌 JSON，不单独建表）';


-- ============================================
-- 19.AI 情景记忆表
-- ============================================

CREATE TABLE IF NOT EXISTS ai_episodic_memories (
    id BIGINT PRIMARY KEY,

    user_id BIGINT NOT NULL COMMENT '用户ID',
    session_id BIGINT DEFAULT NULL COMMENT '来源会话ID',
    project_key VARCHAR(64) NOT NULL DEFAULT '01myBlog' COMMENT '项目隔离键',

    memory_type VARCHAR(32) NOT NULL COMMENT '情景记忆类型：DECISION / EVENT / MILESTONE / PLAN',
    title VARCHAR(120) NOT NULL COMMENT '短标题，方便列表和调试',
    content TEXT NOT NULL COMMENT '压缩后的历史事件描述，必须保留原因',

    importance TINYINT NOT NULL DEFAULT 6 COMMENT '重要性，1-10',
    confidence DECIMAL(3,2) NOT NULL DEFAULT 0.80 COMMENT '可信度，0-1',

    source_message_ids JSON DEFAULT NULL COMMENT '来源消息ID数组',
    content_hash CHAR(64) NOT NULL COMMENT '内容哈希，用于硬去重',

    occurred_at DATETIME NOT NULL COMMENT '事件发生时间，默认取窗口最后一条消息时间',
    last_retrieved_at DATETIME DEFAULT NULL COMMENT '最近一次被召回时间',
    retrieval_count INT NOT NULL DEFAULT 0 COMMENT '召回次数',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_episodic_user_project_hash (user_id, project_key, content_hash),
    KEY idx_episodic_user_project_time (user_id, project_key, occurred_at),
    KEY idx_episodic_user_type (user_id, memory_type),
    KEY idx_episodic_importance (user_id, project_key, importance)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 情景记忆表';


-- ============================================
-- 20.AI 聊天压缩总结表
-- ============================================

CREATE TABLE IF NOT EXISTS ai_conversation_summaries (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    summary TEXT NOT NULL,
    summary_json TEXT,
    covered_until_message_id BIGINT,
    covered_message_count INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    last_compressed_at DATETIME,
    compressing TINYINT NOT NULL DEFAULT 0 COMMENT '是否正在压缩：1=压缩中 0=空闲',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_session (session_id),
    KEY idx_user_session (user_id, session_id)
);

-- ============================================
-- 20.Agent Runtime 运行实例表
-- ============================================
CREATE TABLE IF NOT EXISTS ai_agent_runs (
    id BIGINT PRIMARY KEY COMMENT '主键，MyBatis-Plus ASSIGN_ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    session_id BIGINT COMMENT '关联 AI 会话ID',
    goal VARCHAR(500) NOT NULL COMMENT '用户原始请求（目标）',
    status VARCHAR(32) NOT NULL COMMENT 'RUNNING/COMPLETED/FAILED/CANCELLED/WAITING_USER',
    current_step INT NOT NULL DEFAULT 0 COMMENT '当前步号',
    max_steps INT NOT NULL DEFAULT 5 COMMENT '最大步数边界',
    used_steps INT NOT NULL DEFAULT 0 COMMENT '已用步数',
    context_json TEXT COMMENT '循环上下文（裁剪后的 observations），不含密钥',
    plan_json TEXT COMMENT 'V3.13 Plan Preview 计划（JSON 数组，仅文章域；单列更新写，不复用 updateById）',
    final_answer TEXT COMMENT '最终回答',
    error_message VARCHAR(1000) COMMENT '失败原因',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_agent_run_user (user_id),
    INDEX idx_agent_run_session (session_id),
    -- 启动恢复 / 低频巡检按 status 找 RUNNING 的 run
    -- （AiRunStartupRecoveryRunner / TaskHandoffReconciliationTask），此前只能全表扫
    INDEX idx_agent_run_status (status, id)
) COMMENT 'Agent Runtime 运行实例';


-- ============================================
-- 20. Agent 步骤记录表
-- ============================================
CREATE TABLE IF NOT EXISTS ai_agent_steps (
    id BIGINT PRIMARY KEY COMMENT '主键，MyBatis-Plus ASSIGN_ID',
    agent_run_id BIGINT NOT NULL COMMENT '所属 Agent Run ID',
    step_no INT NOT NULL COMMENT '步号，从 1 开始',
    action_type VARCHAR(64) NOT NULL COMMENT 'QUERY_LEARNING_DASHBOARD/QUERY_MEMORY/SEARCH_RAG/ASK_USER/FINAL_ANSWER',
    thought_summary VARCHAR(500) NULL COMMENT '思考摘要（清洗后，仅展示，非决策依据）',
    input_json TEXT COMMENT '动作输入摘要 JSON',
    output_json TEXT COMMENT '动作输出摘要 JSON（observation）',
    status VARCHAR(32) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED',
    duration_ms BIGINT COMMENT '执行耗时毫秒',
    error_message VARCHAR(1000) COMMENT '失败原因',
    created_at DATETIME NOT NULL
    -- 不单建 (agent_run_id)：后面 ALTER 加的 uk_agent_task_step 最左前缀就是它
) COMMENT 'Agent 步骤记录';


-- ============================================
-- 20.1 Agent token 统计补列
-- ============================================
-- 原先 agent 链路的 LLM 调用一律 `.call().content()`，usage 整个丢弃，
-- 导致 ai_agent_runs / ai_agent_steps 没有任何 token 数据（钱包计费时支出侧是漏的）。
ALTER TABLE ai_agent_runs
ADD COLUMN input_tokens INT NOT NULL DEFAULT 0 COMMENT 'Agent 循环输入 token 累计',
ADD COLUMN output_tokens INT NOT NULL DEFAULT 0 COMMENT 'Agent 循环输出 token 累计',
ADD COLUMN total_tokens INT NOT NULL DEFAULT 0 COMMENT 'Agent 循环总 token 累计';

ALTER TABLE ai_agent_steps
ADD COLUMN input_tokens INT NOT NULL DEFAULT 0 COMMENT '本步决策 LLM 输入 token',
ADD COLUMN output_tokens INT NOT NULL DEFAULT 0 COMMENT '本步决策 LLM 输出 token';


-- ============================================
-- 20.2 父子 run 拓扑（第四步，2026-09-30）
-- ============================================
-- 一次请求 = 一份 TaskPlan，其中的 ARTICLE_ANALYSIS 步骤会在内部起一个 Agent 子 run。
-- 子 run 挂在触发它的外层 Task run 上：
--   - Token 子树按 parent_run_id 查询聚合（父 run 不复制子 run 的 token）
--   - 用户侧 / 开发者侧列表只列根 run（NULL），子 run 对用户不可见
--   - 启动恢复与确认接口据此识别"这不是用户可操作的普通 run"
ALTER TABLE ai_agent_runs
ADD COLUMN parent_run_id BIGINT NULL COMMENT '父 run ID（外层 Task run）；NULL = 根 run';

CREATE INDEX idx_agent_run_parent ON ai_agent_runs (parent_run_id);


-- ============================================
-- 21. 钱包：用户余额账户（一用户一行，零 Redis）
-- ============================================
-- 设计稿：docs/redis/钱包与秒杀计划.md
-- balance 允许为负——预扣门槛是 balance > 0 而不是 balance >= reserved，
-- 所以「余额扣成负数」是正常业务状态，非负约束会让预扣直接报错。
-- 留一个宽松下界是为了让「符号写反 / 扣减跑飞」这类 bug 仍被 DB 拦住。
-- ⚠️ 下界必须与最大预扣额度挂钩：应用启动时校验「最大预扣 × 安全系数 < 1000000」，不满足 fail-fast。
CREATE TABLE IF NOT EXISTS user_wallet (
  id          BIGINT   NOT NULL PRIMARY KEY COMMENT '主键（雪花）',
  user_id     BIGINT   NOT NULL COMMENT '所属用户',
  balance     BIGINT   NOT NULL DEFAULT 0 COMMENT '余额，单位 credit；允许为负（透支一轮）',
  balance_seq BIGINT   NOT NULL DEFAULT 0 COMMENT '余额变更序号，只用于流水严格排序',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_id (user_id),
  CONSTRAINT chk_wallet_balance_floor CHECK (balance >= -1000000)
) COMMENT='用户钱包账户';

-- ============================================
-- 22. 钱包流水（只增不改）
-- ============================================
-- balance_after 快照是对账关键：逐笔回放校验「上一笔.balance_after + 本笔.amount == 本笔.balance_after」。
-- ⚠️ 回放必须按 balance_seq 排序：时间戳会相同，Snowflake id 的大小也不等于钱包行锁的获得顺序。
-- ⚠️ uk_user_idem 是 (user_id, idempotency_key) 而非全局唯一：全局唯一时用户 B 碰巧用了被 A 占掉的 key
--    会「静默成功但没加钱」，加 user_id 前缀至少不会跨用户误伤。
CREATE TABLE IF NOT EXISTS wallet_transaction (
  id              BIGINT       NOT NULL PRIMARY KEY COMMENT '主键（雪花）',
  user_id         BIGINT       NOT NULL COMMENT '所属用户',
  amount          BIGINT       NOT NULL COMMENT '变动额度，有符号：+1000 充值 / -50 消费',
  balance_after   BIGINT       NOT NULL COMMENT '变动后余额快照',
  balance_seq     BIGINT       NOT NULL COMMENT '对应 user_wallet.balance_seq',
  type            VARCHAR(32)  NOT NULL COMMENT 'RECHARGE / AI_RESERVE / AI_REFUND / SECKILL_GRANT',
  biz_type        VARCHAR(32)  NOT NULL COMMENT 'RECHARGE_ORDER / AI_BILLING / SECKILL_ORDER',
  biz_id          VARCHAR(128) NOT NULL COMMENT '业务主键',
  idempotency_key VARCHAR(128) NOT NULL COMMENT '服务端派生的记账幂等键，不能用客户端 Idempotency-Key',
  remark          VARCHAR(255) NULL COMMENT '备注',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_idem (user_id, idempotency_key),
  UNIQUE KEY uk_user_seq (user_id, balance_seq),
  KEY idx_user_id (user_id, id)
) COMMENT='钱包流水（只增不改）';

-- ============================================
-- 23. 充值订单（虚拟支付）
-- ============================================
-- 金额和到账额度都落在订单行上（下单时按套餐快照写入），
-- 以后套餐改价，历史订单仍然可对账。
CREATE TABLE IF NOT EXISTS wallet_recharge_order (
  id            BIGINT      NOT NULL PRIMARY KEY COMMENT '主键（雪花）',
  order_no      VARCHAR(64) NOT NULL COMMENT '服务端生成的订单号',
  user_id       BIGINT      NOT NULL COMMENT '下单用户',
  package_code  VARCHAR(32) NOT NULL COMMENT '套餐编码；自定义金额订单固定为 CUSTOM（不是金额；前端说了不算）',
  pay_amount    BIGINT      NOT NULL COMMENT '应付金额（分，真实货币口径；虚拟支付下仅展示）',
  credit_amount BIGINT      NOT NULL COMMENT '到账额度',
  status        VARCHAR(16) NOT NULL COMMENT 'PENDING / PAID / CLOSED',
  version       INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  expire_at     DATETIME    NULL COMMENT '支付截止时间；空 = 历史订单不过期',
  paid_at       DATETIME    NULL COMMENT '支付时间',
  closed_at     DATETIME    NULL COMMENT '关闭时间',
  close_reason  VARCHAR(32) NULL COMMENT 'TIMEOUT / USER_CANCELLED',
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user (user_id, created_at),
  -- 超时关单任务按 (status, expire_at) 扫，不能全表扫
  KEY idx_order_status_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='充值订单（虚拟支付，回调驱动入账）';

-- ============================================
-- 23.1 充值支付单（一个订单可多次支付尝试）
-- ============================================
-- 订单是业务凭证，支付单是渠道凭证：对账的对象是支付单，不是订单。
-- uk_channel_trade_no 是回调幂等的第二道防线（渠道重复投递同一交易号必须撞唯一键）。
-- callback_payload 真实支付里必须有——出纠纷时「回调原文长什么样」是唯一证据。
CREATE TABLE IF NOT EXISTS wallet_payment (
  id                BIGINT       NOT NULL PRIMARY KEY COMMENT '主键（雪花）',
  payment_no        VARCHAR(64)  NOT NULL COMMENT '商户支付单号（真实场景即 out_trade_no）',
  order_no          VARCHAR(64)  NOT NULL COMMENT '对应 wallet_recharge_order.order_no',
  user_id           BIGINT       NOT NULL COMMENT '下单用户（冗余：回调无登录态，靠它定位）',
  channel           VARCHAR(32)  NOT NULL COMMENT '支付渠道：MOCK /（将来 WECHAT / ALIPAY）',
  channel_trade_no  VARCHAR(64)  NULL COMMENT '渠道交易号（回调回填）',
  pay_amount        BIGINT       NOT NULL COMMENT '应付金额（分），下单时快照',
  status            VARCHAR(16)  NOT NULL COMMENT 'CREATED / SUCCESS / FAILED / CLOSED',
  pay_token         VARCHAR(128) NULL COMMENT '模拟支付页凭证（真实场景是 prepay_id）',
  paid_at           DATETIME     NULL COMMENT '渠道支付成功时间',
  fail_reason       VARCHAR(255) NULL COMMENT '失败原因',
  callback_payload  TEXT         NULL COMMENT '原始回调报文（验签通过后落库）',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_payment_no (payment_no),
  UNIQUE KEY uk_channel_trade_no (channel, channel_trade_no),
  KEY idx_payment_order_no (order_no),
  KEY idx_payment_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='充值支付单（渠道凭证）';

-- ============================================
-- 24. AI 计费单（预扣 / 结算 / 退款）
-- ============================================
-- 状态机只有三态：RESERVED → SETTLED | RELEASED（没有 SETTLEMENT_PENDING）。
-- usage 缺失时按预扣全额结算（actual = reserved）+ [BILLING-USAGE-MISSING] 告警。
-- ⚠️ order_no 不是流水幂等键：一张单在一次调用里要写两笔流水（预扣 + 退款），
--    共用同一个键会让第二笔撞上 uk_user_idem → 事务回滚、钱退不回来。
--    幂等键要按操作类型派生：AI_RESERVE:{order_no} / AI_REFUND:{order_no}。
-- 两道幂等分工：uk_biz 防「重复预扣」，status='RESERVED' 条件更新防「重复结算」。
CREATE TABLE IF NOT EXISTS ai_billing_order (
  id                BIGINT      NOT NULL PRIMARY KEY COMMENT '主键（雪花）',
  order_no          VARCHAR(64) NOT NULL COMMENT '业务单号，流水幂等键由「操作类型 + 它」派生',
  user_id           BIGINT      NOT NULL COMMENT '付费用户',
  biz_type          VARCHAR(32) NOT NULL COMMENT 'CHAT / AGENT / WORKFLOW_ACTION',
  biz_id            VARCHAR(128) NOT NULL COMMENT '调用前已存在的计费键',
  resource_id       VARCHAR(128) NULL COMMENT '调用后回填：assistantMessageId / agentRunId / stepLogId',
  status            VARCHAR(16) NOT NULL COMMENT 'RESERVED / SETTLED / RELEASED',
  reserved_credit   BIGINT      NOT NULL COMMENT '预扣额度（来自该路径的可执行硬上限）',
  actual_credit     BIGINT      NULL COMMENT '实际结算额度（SETTLED 时写入）',
  prompt_tokens     INT         NULL COMMENT '输入 token（仅观测）',
  completion_tokens INT         NULL COMMENT '输出 token（仅观测）',
  total_tokens      INT         NULL COMMENT '总 token（V1 结算依据）',
  version           INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  expire_at         DATETIME    NOT NULL COMMENT '超时释放兜底时间（进程崩溃时才走得到）',
  created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  settled_at        DATETIME    NULL COMMENT '结算时间',
  -- order_no 由 OrderNoGenerator 生成（前缀+秒级时间戳+6位随机码），本身没有唯一性保证。
  -- 三个 UPDATE 都用 WHERE order_no = ? 定位，一旦撞号会更新到错误的单上——
  -- 唯一约束不只是为了查询快，它是「不更新错单」的最后一道防线。
  UNIQUE KEY uk_order_no (order_no),
  UNIQUE KEY uk_biz (biz_type, biz_id),
  KEY idx_status_expire (status, expire_at),
  KEY idx_user_status (user_id, status)
) COMMENT='AI 计费单（预扣 / 结算 / 退款）';

-- ============================================
-- 25. 秒杀活动
-- ============================================
CREATE TABLE IF NOT EXISTS seckill_activity (
  id            BIGINT      NOT NULL PRIMARY KEY COMMENT '主键（每日自动活动 = yyyyMMdd；手工建的用雪花或 yyyyMMddHHmm）',
  name          VARCHAR(64) NOT NULL COMMENT '活动名称',
  total_stock   INT         NOT NULL COMMENT '总库存',
  sold_count    INT         NOT NULL DEFAULT 0 COMMENT 'DB 侧最终账本',
  credit_amount BIGINT      NOT NULL COMMENT '抢到发多少额度',
  start_at      DATETIME    NOT NULL COMMENT '开始时间',
  end_at        DATETIME    NOT NULL COMMENT '结束时间',
  status        VARCHAR(16) NOT NULL COMMENT 'DRAFT / ACTIVE / ENDED',
  status_changed_at DATETIME NULL COMMENT '状态最后流转时间（只在 CAS 流转时写；抢购更新 sold_count 不会动它）',
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_status_time (status, start_at, end_at)
) COMMENT='秒杀活动';

-- ============================================
-- 26. 秒杀订单（一人一单）
-- ============================================
-- uk_user_activity 是一人一单的最终防线：Redis 的 Set 只挡住绝大多数重复点击，
-- DB 唯一索引负责兜住漏网的那一个。
-- FAILED_RETRY = 技术失败重试超限的死信终态（见设计稿 §6.5）。
CREATE TABLE IF NOT EXISTS seckill_order (
  id            BIGINT      NOT NULL PRIMARY KEY COMMENT '主键（雪花）',
  activity_id   BIGINT      NOT NULL COMMENT '所属活动',
  user_id       BIGINT      NOT NULL COMMENT '抢购用户',
  credit_amount BIGINT      NOT NULL COMMENT '到账额度',
  status        VARCHAR(16) NOT NULL COMMENT 'GRANTED / FAILED / FAILED_RETRY',
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_activity (user_id, activity_id),
  KEY idx_activity_status (activity_id, status)
) COMMENT='秒杀订单（一人一单）';

-- ============================================
-- 27. 秒杀活动：无种子数据
-- ============================================
-- ⚠️ 这里**刻意不灌活动**。活动由 SeckillDailyTask 每天自动创建（见设计稿 §6.8）：
-- 每天固定时段一场、DRAFT 起手，到点由 SeckillLifecycleTask 自动激活 + 预热。
--
-- 2026-09-20 之前这里有一条 id=1、100 份、30 天窗口的 ACTIVE 种子。
-- 它和自动开场冲突——会一直挂在列表上且能抢，让人以为「每天 50 份」是坏的，所以删掉了。
-- 手工建临时活动仍然可以，但注意 id 段位约定（见 docs/redis/wallet-verification-guide.md §七）：
--   8 位 yyyyMMdd = 自动开场占用，别用；12 位 yyyyMMddHHmm 或 19 位雪花都行。

-- ============================================
-- 初始数据：预设分类（2026-10-18 上线前调整为 6 类）
-- ⚠️ 旧库不要重跑本文件（非幂等）；旧库升级用 db/migrate-categories-2026-10-18.sql
-- ============================================
INSERT INTO categories (name, code, description, sort_order) VALUES
    ('后端', 'backend', 'Java、Spring Boot、后端开发相关内容', 10),
    ('前端', 'frontend', 'Vue、TypeScript、前端工程化相关内容', 20),
    ('AI / Agent', 'ai-agent', 'AI、Agent、coding agent 相关内容', 30),
    ('开发工具', 'dev-tools', '开发工具、效率工具、部署与运维', 40),
    ('代码人生', 'code-life', '程序员成长、职业感悟、杂谈', 50),
    ('阅读', 'reading', '读书笔记、技术书籍、文章分享', 60)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    sort_order = VALUES(sort_order);

-- ============================================
-- 28. 5b：WORKFLOW_HANDOFF 暂停 / 恢复（2026-10-01）
-- ============================================
-- 为什么追加在这里：本文件是「建表 + 增量补列」的累积脚本，新列一律以 ALTER 追加，
-- 不改上面的历史建表语句（对已经存在的库没有意义）。
-- ⚠️ 这几条同样要**手工在本地 MySQL 开发库执行**；测试库是 H2、每次由 schema.sql 重建，
--    「测试全绿」不代表开发库有这些列（见 AGENTS.md 的 DDL 约定，2026-09-30 踩过）。

-- 外层 TaskPlan 的正式快照。**不复用 plan_json**：那是 V3.13 Plan Preview 的 List<String> 展示计划。
ALTER TABLE ai_agent_runs
ADD COLUMN task_plan_json TEXT NULL COMMENT '5b 外层 TaskPlan 快照（带 schemaVersion 的 envelope）';

-- 外层步骤的稳定标识：预创建 PENDING 行时写入，恢复时按它对齐；内部 Agent 步骤为 NULL。
ALTER TABLE ai_agent_steps
ADD COLUMN task_step_id VARCHAR(64) NULL COMMENT '5b 外层 TaskStep 标识（内部 Agent 步骤为 NULL）',
ADD UNIQUE KEY uk_agent_task_step (agent_run_id, task_step_id);

-- Workflow → 外层任务的父关联。普通独立 Workflow 两列为 NULL；
-- MySQL 唯一索引允许多个 NULL，所以不会互相冲突。
ALTER TABLE ai_workflow_runs
ADD COLUMN task_run_id BIGINT NULL COMMENT '5b 由 WORKFLOW_HANDOFF 创建时的外层 run id',
ADD COLUMN task_step_id VARCHAR(64) NULL COMMENT '5b 外层 TaskStep 标识',
ADD UNIQUE KEY uk_workflow_task_step (task_run_id, task_step_id);

-- ============================================
-- 29. 站内通知表（2026-10-11）
-- ============================================
-- 设计稿：docs/notification/通知中心设计.md
-- 通知行与业务写**在同一个事务里**（不是 afterCommit、也不是 outbox）：同库单表 INSERT，
-- 业务成功 ⟺ 通知在，不需要补偿、不需要对账。为什么不用 AfterCommitExecutor 见设计稿 §2.1。
-- ⚠️ 同样要**手工在本地 MySQL 开发库执行**；测试库是 H2、每次由 schema.sql 重建。
--
-- ⚠️ 本表 id **由应用生成（雪花）**，所以没有 AUTO_INCREMENT。
--    全站 mybatis-plus.id-type 是 auto（application.yml:127），要雪花必须由实体**显式**声明
--    @TableId(type = IdType.ASSIGN_ID)（先例：ArticleComments:15）。
--    漏写的下场与 articles 表那个坑正好相反：insert 直接报 Field 'id' doesn't have a default value。

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '主键（雪花，由应用生成，故无 AUTO_INCREMENT）',
    receiver_id BIGINT UNSIGNED NOT NULL COMMENT '接收者用户 ID —— 所有查询/更新都必须带上它',
    actor_id BIGINT UNSIGNED NOT NULL COMMENT '触发者用户 ID（渲染"谁干的"）',
    type VARCHAR(32) NOT NULL COMMENT '事件类型，取值见设计稿 §5',
    target_id BIGINT UNSIGNED NOT NULL COMMENT '事件指向的对象：文章 / 新评论 / 被关注者',
    article_id BIGINT UNSIGNED DEFAULT NULL COMMENT '跳转用文章 ID（评论类事件也带上，省一次回查）',
    is_read TINYINT NOT NULL DEFAULT 0 COMMENT '0=未读 1=已读',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 幂等：同一人（actor）对同一对象（target）的同一类事件，只打扰一次。
    -- ⚠️ 四列全部 NOT NULL：MySQL 唯一索引**不约束 NULL**，任一列可空都会让幂等静默失效。
    -- 它不能与下面两条合并——唯一约束加任何一列都会让"同一件事"能插进多行。
    UNIQUE KEY uk_notification (receiver_id, type, actor_id, target_id),

    -- 列表：WHERE receiver_id=? ORDER BY created_at DESC LIMIT ?
    -- **必须独立于 uk_notification**：那个索引最左只到 receiver_id，created_at 不在里面，
    -- 排序会退化成 filesort（把该用户全部通知排一遍再取 20 条）。
    -- 本表是"有页码语义的后台型列表"，不做游标分页（见 api-convention §返回格式）。
    INDEX idx_receiver_created (receiver_id, created_at),

    -- 未读数：WHERE receiver_id=? AND is_read=0
    -- 带上 created_at 是**故意多花几字节换覆盖**：将来加"只看未读"筛选时
    -- （通知的常见形态）它能直接有序取，不必再补一条索引。
    INDEX idx_receiver_read_created (receiver_id, is_read, created_at),

    -- 保留期清理用：WHERE created_at < ?
    -- **上面两条都用不上它**——它们都以 receiver_id 开头，而这个查询不带 receiver。
    -- 少了这条，清理任务就是全表扫描（表越该清越扫得慢）
    INDEX idx_created_at (created_at)

    -- 不建索引的列，理由是"没有对应查询"，不是"列不重要"：
    --   article_id —— 渲染是「按通知行的 article_id 批量回查文章」，没有反向查询；
    --                 删文章走渲染时降级（设计稿 §8.2），不做级联更新，所以不需要。
    --   actor_id   —— 没有任何"按触发者查通知"的入口。
    --   is_read    —— 不存在跨用户的"全站未读"查询，它只在 receiver_id 确定后才有意义。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知表';


-- ============================================
-- 30. 文章索引同步 outbox 表（2026-10-22 第二批设计稿：docs/mq/文章索引同步MQ迁移设计.md）
-- ============================================
-- 目的：把"文章写库 → RAG 向量索引 + 站内搜索索引"从 @Async + Redis ZSet 重试
--       换成 DB outbox（事务内落事件）→ RocketMQ（按 articleId 顺序）→ 消费端回查 DB 双索引同步。
--
-- 为什么单独建表而不是复用 Redis：
--   ① 文章写入与"索引事件存在"必须原子（同事务），Redis 做不到；
--   ② 事件要可对账、可回放、可看积压（PENDING/SENDING/SENT/FAILED 状态机）；
--   ③ outbox 是本批唯一写入方，表小、写入频率 = 文章写频率，不构成热点。
--
-- 状态机：PENDING → SENDING（带 60s 租约）→ SENT；
--         投递失败回 PENDING 指数退避（5s→300s 封顶），20 次后转 FAILED 留给对账/人工。
--         租约过期由 relay 恢复成 PENDING 重投（至少一次，消费端回查 DB 幂等）。
--
-- ⚠️ 本表 id 用 AUTO_INCREMENT：outbox 只是投递记录，不对外暴露、不参与业务关联，
--    不需要雪花；而且自增天然给出"入表顺序"，relay 按 id 顺序投递。
--
-- ⚠️ 与 db/schema 同步约定：测试库是 H2（每次由 src/test/resources/schema.sql 重建），
--    改这里必须同步改 schema.sql（H2 语法：DATETIME→TIMESTAMP、去掉 COMMENT/ENGINE）。

CREATE TABLE IF NOT EXISTS article_index_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键（投递顺序）',
    article_id BIGINT UNSIGNED NOT NULL COMMENT '文章 ID（同一文章的事件按 id 顺序投递）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT/FAILED',
    attempts INT NOT NULL DEFAULT 0 COMMENT 'relay 投递失败次数（消费失败由 MQ 重试，不计这里）',
    next_retry_at DATETIME NOT NULL COMMENT 'PENDING 状态下最早可投递时间',
    locked_until DATETIME DEFAULT NULL COMMENT 'SENDING 租约到期时间（崩溃恢复用）',
    sent_at DATETIME DEFAULT NULL COMMENT '成功投递到 RocketMQ 的时间',
    trace_id VARCHAR(64) DEFAULT NULL COMMENT '写入请求的 traceId（relay 线程没有请求上下文，必须落库）',
    last_error VARCHAR(500) DEFAULT NULL COMMENT '最近一次投递失败原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- relay 每 2s 扫一次到期行：WHERE status='PENDING' AND next_retry_at<=NOW() ORDER BY id LIMIT n
    INDEX idx_outbox_due (status, next_retry_at),

    -- 排查"这篇文章为什么还没索引" / 后续做同文章事件合并时用
    INDEX idx_outbox_article (article_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章索引同步 outbox（事务内落事件，relay 投递）';
