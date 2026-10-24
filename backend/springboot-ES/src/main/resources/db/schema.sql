SET NAMES utf8mb4;

-- =====================================================================
-- 电商销售数据洞察看板 建表脚本（《后端开发文档》§3.1）
-- 由 spring.sql.init.schema-locations 在启动时执行（幂等：IF NOT EXISTS）
-- =====================================================================

-- 订单表（字段与接口文档 3.1 数据字典一一对应）
CREATE TABLE IF NOT EXISTS orders (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  order_id      VARCHAR(32)  NOT NULL COMMENT '订单号，如 ORD20260901001',
  product_name  VARCHAR(128) NOT NULL COMMENT '商品名称',
  category      VARCHAR(32)  NOT NULL COMMENT '品类',
  price         DECIMAL(12,2) NOT NULL COMMENT '商品单价（元）',
  quantity      INT          NOT NULL COMMENT '购买数量',
  total_amount  DECIMAL(14,2) NOT NULL COMMENT '订单总金额 = 单价 × 数量',
  order_time    DATETIME     NOT NULL COMMENT '下单时间',
  user_id       VARCHAR(32)  NOT NULL COMMENT '用户 ID',
  user_type     VARCHAR(8)   NOT NULL COMMENT 'new / old',
  pay_type      VARCHAR(16)  NOT NULL COMMENT 'alipay / wechat / card',
  promo_flag    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否促销单 0/1',
  region        VARCHAR(32)  DEFAULT NULL COMMENT '收货省份',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_id (order_id),
  KEY idx_order_time (order_time),
  KEY idx_category_time (category, order_time),
  KEY idx_user (user_id),
  KEY idx_pay_type (pay_type),
  KEY idx_promo_time (promo_flag, order_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 销售目标表（接口文档 3.2）
CREATE TABLE IF NOT EXISTS sales_target (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  period_type   VARCHAR(16)  NOT NULL COMMENT '周期类型，当前仅 month',
  period_value  VARCHAR(16)  NOT NULL COMMENT '周期值 yyyy-MM',
  category      VARCHAR(32)  NOT NULL COMMENT '品类，ALL 表示总目标',
  target_amount DECIMAL(14,2) NOT NULL COMMENT '目标销售额（元）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_period_category (period_type, period_value, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售目标表';

-- 导入任务表（A18/A19）
CREATE TABLE IF NOT EXISTS import_task (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  task_id      VARCHAR(64)  NOT NULL COMMENT '任务 ID，如 imp_20260916_001',
  file_name    VARCHAR(255) NOT NULL,
  file_hash    CHAR(64)     NOT NULL COMMENT '文件 SHA-256，用于重复导入检测(409)',
  append_mode  TINYINT      NOT NULL DEFAULT 0 COMMENT '0 覆盖 / 1 追加',
  status       VARCHAR(16)  NOT NULL COMMENT 'PROCESSING/SUCCESS/PARTIAL/FAILED',
  total_rows   INT          NOT NULL DEFAULT 0,
  valid_rows   INT          NOT NULL DEFAULT 0,
  error_rows   INT          NOT NULL DEFAULT 0,
  start_time   DATETIME     NOT NULL,
  end_time     DATETIME     DEFAULT NULL,
  fail_reason  VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_id (task_id),
  KEY idx_status (status),
  KEY idx_file_hash (file_hash, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CSV 导入任务';

-- 导入错误明细表（A19 的 errors 数组）
CREATE TABLE IF NOT EXISTS import_task_error (
  id        BIGINT       NOT NULL AUTO_INCREMENT,
  task_id   VARCHAR(64)  NOT NULL,
  row_no    INT          NOT NULL COMMENT 'CSV 行号（含表头，从 1 开始）',
  field     VARCHAR(64)  DEFAULT NULL COMMENT '出错字段',
  message   VARCHAR(255) NOT NULL COMMENT '错误原因',
  PRIMARY KEY (id),
  KEY idx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入错误行明细';

-- P1：预聚合日报表（工程化增强，见《后端开发文档》§9.1/§9.4）
CREATE TABLE IF NOT EXISTS daily_summary (
  id           BIGINT NOT NULL AUTO_INCREMENT,
  stat_date    DATE   NOT NULL,
  category     VARCHAR(32) NOT NULL,
  pay_type     VARCHAR(16) NOT NULL DEFAULT 'ALL',
  user_type    VARCHAR(8)  NOT NULL DEFAULT 'ALL',
  order_count  INT          NOT NULL DEFAULT 0,
  total_sales  DECIMAL(16,2) NOT NULL DEFAULT 0,
  user_count   INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dim (stat_date, category, pay_type, user_type),
  KEY idx_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日预聚合（可选）';