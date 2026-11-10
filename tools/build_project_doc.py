# -*- coding: utf-8 -*-
"""Build the course deliverable project document (02-项目文档) for ES."""
import os
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_LINE_SPACING
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.section import WD_SECTION
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

BODY_FONT = "微软雅黑"
CODE_FONT = "Consolas"
NAVY = RGBColor(0x0E, 0x3F, 0x8C)
BLUE = RGBColor(0x1E, 0x4F, 0xA8)
GREY = RGBColor(0x8B, 0x97, 0xA8)
DARK = RGBColor(0x22, 0x22, 0x22)

USABLE = 16.79  # cm, Letter width minus 2*2.4cm margins


def set_run_font(run, name=BODY_FONT, size=10.5, bold=False, color=None, italic=False):
    run.font.name = name
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.italic = italic
    if color is not None:
        run.font.color.rgb = color
    rPr = run._element.get_or_add_rPr()
    rFonts = rPr.find(qn("w:rFonts"))
    if rFonts is None:
        rFonts = OxmlElement("w:rFonts")
        rPr.append(rFonts)
    rFonts.set(qn("w:eastAsia"), name)
    rFonts.set(qn("w:ascii"), name)
    rFonts.set(qn("w:hAnsi"), name)


def shade(element_pr, fill):
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:color"), "auto")
    shd.set(qn("w:fill"), fill)
    element_pr.append(shd)


def para(doc, text="", size=10.5, bold=False, color=DARK, align=None, indent=True,
         space_before=0, space_after=4, line=1.5, font=BODY_FONT, keep_next=False):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    if align is not None:
        p.alignment = align
    if indent:
        pf.first_line_indent = Cm(size * 0.074)  # 2 chars
    pf.space_before = Pt(space_before)
    pf.space_after = Pt(space_after)
    pf.line_spacing = line
    if keep_next:
        pf.keep_with_next = True
    if text:
        r = p.add_run(text)
        set_run_font(r, font, size, bold, color)
    return p


def h1(doc, text):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.space_before = Pt(16)
    pf.space_after = Pt(6)
    pf.keep_with_next = True
    r = p.add_run(text)
    set_run_font(r, BODY_FONT, 15, True, NAVY)
    # bottom rule
    pPr = p._element.get_or_add_pPr()
    pbdr = OxmlElement("w:pBdr")
    bottom = OxmlElement("w:bottom")
    bottom.set(qn("w:val"), "single")
    bottom.set(qn("w:sz"), "8")
    bottom.set(qn("w:space"), "2")
    bottom.set(qn("w:color"), "C9D6E8")
    pbdr.append(bottom)
    pPr.append(pbdr)
    return p


def h2(doc, text):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.space_before = Pt(10)
    pf.space_after = Pt(4)
    pf.keep_with_next = True
    r = p.add_run(text)
    set_run_font(r, BODY_FONT, 13, True, BLUE)
    return p


def bullet(doc, text, size=10.5):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.left_indent = Cm(0.75)
    pf.first_line_indent = Cm(-0.35)
    pf.space_before = Pt(0)
    pf.space_after = Pt(2)
    pf.line_spacing = 1.5
    r = p.add_run("· ")
    set_run_font(r, BODY_FONT, size, False, BLUE)
    r2 = p.add_run(text)
    set_run_font(r2, BODY_FONT, size, False, DARK)
    return p


def code(doc, text, size=8.5):
    lines = text.rstrip("\n").split("\n")
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.left_indent = Cm(0.4)
    pf.right_indent = Cm(0.2)
    pf.space_before = Pt(3)
    pf.space_after = Pt(6)
    pf.line_spacing = 1.0
    pPr = p._element.get_or_add_pPr()
    shade(pPr, "F4F6FA")
    pbdr = OxmlElement("w:pBdr")
    for side in ("left",):
        b = OxmlElement(f"w:{side}")
        b.set(qn("w:val"), "single")
        b.set(qn("w:sz"), "18")
        b.set(qn("w:space"), "4")
        b.set(qn("w:color"), "9DB2D0")
        pbdr.append(b)
    pPr.append(pbdr)
    for i, line in enumerate(lines):
        r = p.add_run(line)
        set_run_font(r, CODE_FONT, size, False, RGBColor(0x33, 0x33, 0x33))
        r._element.get_or_add_rPr().find(qn("w:rFonts")).set(qn("w:eastAsia"), BODY_FONT)
        if i < len(lines) - 1:
            r.add_break()
    return p


def note(doc, text):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.left_indent = Cm(0.4)
    pf.space_before = Pt(2)
    pf.space_after = Pt(6)
    pf.line_spacing = 1.3
    pPr = p._element.get_or_add_pPr()
    shade(pPr, "FFF7E6")
    r = p.add_run("说明：")
    set_run_font(r, BODY_FONT, 9, True, RGBColor(0x8A, 0x6D, 0x00))
    r2 = p.add_run(text)
    set_run_font(r2, BODY_FONT, 9, False, RGBColor(0x6B, 0x55, 0x00))
    return p


def table(doc, headers, rows, widths, font_size=9, header_fill="EDF2F9", align_center_cols=None):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    tblPr = t._tbl.tblPr
    layout = OxmlElement("w:tblLayout")
    layout.set(qn("w:type"), "fixed")
    tblPr.append(layout)
    # header
    hdr = t.rows[0]
    trPr = hdr._tr.get_or_add_trPr()
    th = OxmlElement("w:tblHeader")
    trPr.append(th)
    for i, h in enumerate(headers):
        cell = hdr.cells[i]
        cell.width = Cm(widths[i])
        cell.text = ""
        p = cell.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.space_before = Pt(1)
        p.paragraph_format.space_after = Pt(1)
        p.paragraph_format.line_spacing = 1.1
        r = p.add_run(h)
        set_run_font(r, BODY_FONT, font_size, True, NAVY)
        shade(cell._tc.get_or_add_tcPr(), header_fill)
    for row_vals in rows:
        row = t.add_row()
        for i, val in enumerate(row_vals):
            cell = row.cells[i]
            cell.width = Cm(widths[i])
            cell.text = ""
            p = cell.paragraphs[0]
            p.paragraph_format.space_before = Pt(1)
            p.paragraph_format.space_after = Pt(1)
            p.paragraph_format.line_spacing = 1.15
            if align_center_cols and i in align_center_cols:
                p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            r = p.add_run(str(val))
            set_run_font(r, BODY_FONT, font_size, False, DARK)
    # spacing paragraph after table
    para(doc, "", indent=False, space_after=2)
    return t


def build(out_path):
    doc = Document()
    # page setup mirroring the template (Letter, margins 2.4/2.2 cm)
    sec = doc.sections[0]
    sec.page_width = Cm(21.59)
    sec.page_height = Cm(27.94)
    sec.left_margin = Cm(2.4)
    sec.right_margin = Cm(2.4)
    sec.top_margin = Cm(2.2)
    sec.bottom_margin = Cm(2.2)

    normal = doc.styles["Normal"]
    normal.font.name = BODY_FONT
    normal.font.size = Pt(10.5)
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), BODY_FONT)
    normal.paragraph_format.space_after = Pt(4)
    normal.paragraph_format.line_spacing = 1.5

    # ---------------- cover ----------------
    para(doc, "三峡大学 · 计算机与信息学院", 11, False, GREY, WD_ALIGN_PARAGRAPH.CENTER, indent=False, space_after=4)
    para(doc, "数字看板项目文档", 19, True, NAVY, WD_ALIGN_PARAGRAPH.CENTER, indent=False, space_after=4)
    para(doc, "《Web开发技术》结课项目 · 大数据数字看板", 11, False, BLUE, WD_ALIGN_PARAGRAPH.CENTER,
         indent=False, space_after=14)

    cover_rows = [
        ["组号", "第 1 组（3 人）", "选题名称", "电商销售数据洞察看板"],
        ["成员 1", "杨程鹏（202410760225）", "角色", "组长：整体规划 / 需求分析 / 文档统筹 / 数据工程（ETL）/ 后端与数据库"],
        ["成员 2", "陈梦飞（202410760215）", "角色", "前端开发 / 测试 / 可视化规范"],
        ["成员 3", "朱邦煦（202410760241）", "角色", "设计规范 / 答辩材料"],
        ["提交日期", "2026-10-27", "仓库地址", "本地 Git 仓库 D:\\ESDID（2 次提交，待上传远程）"],
    ]
    table(doc, ["项目", "内容", "项目", "内容"], [], [2.1, 6.2, 2.1, 6.3], font_size=10)
    # re-fill the cover table manually (no header row in the design)
    t = doc.tables[-1]
    # remove the empty header row added by helper
    tbl = t._tbl
    tbl.remove(t.rows[0]._tr)
    for row_vals in cover_rows:
        row = t.add_row()
        for i, val in enumerate(row_vals):
            cell = row.cells[i]
            cell.width = Cm([2.1, 6.2, 2.1, 6.3][i])
            cell.text = ""
            p = cell.paragraphs[0]
            p.paragraph_format.space_before = Pt(1)
            p.paragraph_format.space_after = Pt(1)
            r = p.add_run(val)
            set_run_font(r, BODY_FONT, 9.5, i % 2 == 0, NAVY if i % 2 == 0 else DARK)
            if i % 2 == 0:
                shade(cell._tc.get_or_add_tcPr(), "EDF2F9")

    para(doc, "", indent=False, space_after=6)

    # ---------------- 一、需求分析 ----------------
    h1(doc, "一、需求分析")

    h2(doc, "1.1 项目背景")
    para(doc, "本项目选题来自电商运营中一个很具体的问题：订单数据每天都在增长，但要回答“上月卖了多少、哪个品类在涨、"
              "促销到底有没有拉动”这类问题，往往要临时写 SQL 或找技术同学导 Excel，口径不一致、用完就丢，下一次还得重来。"
              "这个项目的目标，是把“取数—聚合—可视化—解释”固化为一套可以反复打开的看板。")
    para(doc, "数据侧采用 Kaggle 公开的 Olist 巴西电商订单数据集（99,441 单、112,650 条明细），经过清洗、币种换算与字段映射后"
              "落库 MySQL；服务侧用 Spring Boot 提供 19 个聚合与明细接口；展示侧用 Vue 3 + ECharts 做成六个页面的看板；"
              "并在此基础上增加一个 Text-to-SQL 助手，不会写 SQL 的人也能用中文提问。项目覆盖了课程要求的"
              "“数据获取—存储—接口封装—可视化—AI 协同”完整链路。")

    h2(doc, "1.2 目标用户与使用场景")
    para(doc, "看板的使用者不是高并发的互联网用户，而是三类具体角色，他们的使用方式决定了本项目的架构取舍：")
    bullet(doc, "电商运营：每天打开看板看昨日 KPI 与月度趋势，按品类、支付方式、促销标记下钻，发现异常后再看订单明细。")
    bullet(doc, "数据分析：用 RFM、品类帕累托、时段分布判断用户结构与品类集中度，需要把明细导出 CSV 做二次分析。")
    bullet(doc, "课程答辩与评委：从启动工程、导入 CSV、操作看板到 AI 问答完整走一遍，重点验证接口是否真实、数字是否与数据库一致。")
    para(doc, "因此架构上采用单机 MySQL + Caffeine 本地缓存 + 线程池异步导入的轻量方案：没有引入消息队列、"
              "没有做多租户与分布式，把复杂度留给“口径正确”和“能讲清楚”，而不是堆中间件。")

    h2(doc, "1.3 功能清单")
    table(doc,
          ["#", "功能点", "说明", "负责人", "完成情况"],
          [
              ["1", "全局筛选", "日期区间、品类、支付方式、用户类型、促销标记、订单号/商品名关键字，任一项变更后全屏统一刷新", "陈梦飞", "已完成"],
              ["2", "KPI 总览", "销售额、订单数、客单价、热销商品数、用户数、新客率、复购率 7 个指标卡", "杨程鹏 / 陈梦飞", "已完成"],
              ["3", "销售趋势", "按日 / 周 / 月聚合，支持环比与同比两条对比线（A5 / A7）", "杨程鹏 / 陈梦飞", "已完成"],
              ["4", "商品排行", "销售额 Top10 与销量 Top10 切换，可限定品类与时间范围（A4）", "杨程鹏 / 陈梦飞", "已完成"],
              ["5", "品类结构", "品类销售额占比与帕累托累计曲线，标出 80% 分界线（A9）", "杨程鹏 / 陈梦飞", "已完成"],
              ["6", "订单明细", "分页、排序、关键字检索、单行下钻查看详情（A2 / A6）", "杨程鹏 / 陈梦飞", "已完成"],
              ["7", "CSV 导出", "按当前筛选条件流式导出，Excel 打开不乱码（A12）", "杨程鹏", "已完成"],
              ["8", "CSV 导入", "异步导入任务、进度轮询、错误行报告、同一文件重复导入拦截（A18 / A19）", "杨程鹏", "已完成"],
              ["9", "用户分析", "RFM 四象限分层、支付方式占比、24 小时下单时段分布（A11 / A16）", "杨程鹏 / 陈梦飞", "已完成"],
              ["10", "营销活动", "618、双 11、黑五、双 12 的活动期 ROI 与盈亏平衡线（A15）", "杨程鹏 / 陈梦飞", "已完成，拉动率口径待统一"],
              ["11", "目标达成", "月度品类目标与仪表盘进度，展示达成率与剩余天数（A13 / A14）", "杨程鹏 / 陈梦飞", "已完成"],
              ["12", "AI 智能问答", "中文提问 → 生成 SQL → 安全校验 → 执行 → 结论 + ECharts 图，SSE 流式输出", "杨程鹏", "已完成，需启动 :8000 服务"],
              ["13", "界面设计规范与答辩材料", "六页视觉规范（配色/字号/图表样式）、答辩 PPT 与讲稿、演示视频脚本", "朱邦煦", "已完成"],
          ],
          [1.0, 2.9, 8.5, 2.1, 2.2], font_size=8.5, align_center_cols={0, 3})

    # ---------------- 二、数据设计 ----------------
    h1(doc, "二、数据设计")

    h2(doc, "2.1 数据来源")
    table(doc,
          ["项目", "内容"],
          [
              ["来源类型", "开放数据集（Kaggle）"],
              ["来源名称与链接", "Brazilian E-Commerce Public Dataset by Olist，https://www.kaggle.com/datasets/olistbr/brazilian-ecommerce"
                                 "（CC BY-NC-SA 4.0，仅用于课程与非商业作品集，需署名）"],
              ["获取日期", "2026-09-16（ETL 产物生成时间 2026-09-16T13:44:07Z）"],
              ["总行数", "原始订单 99,441 行 / 订单明细 112,650 行 / 支付记录 103,886 行；清洗后入库订单 44,375 行"],
              ["文件与编码", "data/out/orders.csv（UTF-8 无 BOM，10 列，可直接走导入接口）；data/out/init-data.sql（94,657 行，含订单与目标数据）"],
          ],
          [3.4, 13.3], font_size=9)
    note(doc, "任务书要求数据量 ≥10 万行。本项目按“订单”粒度建模，入库 44,375 行，未达到 10 万行；原始数据集订单 99,441 行、"
               "明细 112,650 行，若增加订单明细表（order_items）即可满足量级。提交前需与任课教师确认口径，或补做明细表，"
               "这一点已列入 7.2 的改进方向。")

    h2(doc, "2.2 字段说明")
    para(doc, "核心表 orders 的字段与接口文档数据字典一一对应，其余 4 张表（销售目标、导入任务、导入错误明细、"
              "每日预聚合）见 2.3 建表语句。")
    table(doc,
          ["字段名", "类型", "含义", "是否可空", "说明 / 取值范围"],
          [
              ["id", "BIGINT", "自增主键", "否", "内部主键，不对外暴露"],
              ["order_id", "VARCHAR(32)", "订单号", "否", "唯一键 uk_order_id，如 ORD20170105001"],
              ["product_name", "VARCHAR(128)", "商品名称", "否", "Olist 无商品名，按“品类 + 商品 ID 前 6 位”构造"],
              ["category", "VARCHAR(32)", "品类", "否", "手机 / 电脑 / 服饰 / 美妆 / 食品 / 家电 / 其他"],
              ["price", "DECIMAL(12,2)", "商品单价（元）", "否", "订单明细加权均价，BRL→CNY 汇率 1.45"],
              ["quantity", "INT", "购买数量", "否", "取订单明细行数，Olist 每行一件"],
              ["total_amount", "DECIMAL(14,2)", "订单总金额", "否", "= 单价 × 数量，不含运费，保证导入校验一致"],
              ["order_time", "DATETIME", "下单时间", "否", "索引 idx_order_time；区间查询左闭右开"],
              ["user_id", "VARCHAR(32)", "用户 ID", "否", "customer_unique_id 重编码为 U10001…，保证复购可算"],
              ["user_type", "VARCHAR(8)", "用户类型", "否", "new / old，按该用户首单日期划分"],
              ["pay_type", "VARCHAR(16)", "支付方式", "否", "alipay / wechat / card"],
              ["promo_flag", "TINYINT", "是否促销单", "否", "0 / 1，大促区间 + 周末记为 1"],
              ["region", "VARCHAR(32)", "收货省份", "是", "customer_state 映射中国省份（演示映射），供地图使用"],
              ["created_at", "DATETIME", "入库时间", "否", "默认 CURRENT_TIMESTAMP"],
          ],
          [3.1, 2.6, 4.0, 1.6, 5.4], font_size=8.5, align_center_cols={3})

    h2(doc, "2.3 建表语句")
    para(doc, "以下为 MySQL 8 建表脚本（resources/db/schema.sql，启动时由 spring.sql.init 执行，全部 IF NOT EXISTS 幂等）：")
    code(doc, """CREATE TABLE IF NOT EXISTS orders (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  order_id      VARCHAR(32)   NOT NULL COMMENT '订单号，如 ORD20170105001',
  product_name  VARCHAR(128)  NOT NULL COMMENT '商品名称',
  category      VARCHAR(32)   NOT NULL COMMENT '品类',
  price         DECIMAL(12,2) NOT NULL COMMENT '商品单价（元）',
  quantity      INT           NOT NULL COMMENT '购买数量',
  total_amount  DECIMAL(14,2) NOT NULL COMMENT '订单总金额 = 单价 × 数量',
  order_time    DATETIME      NOT NULL COMMENT '下单时间',
  user_id       VARCHAR(32)   NOT NULL COMMENT '用户 ID',
  user_type     VARCHAR(8)    NOT NULL COMMENT 'new / old',
  pay_type      VARCHAR(16)   NOT NULL COMMENT 'alipay / wechat / card',
  promo_flag    TINYINT       NOT NULL DEFAULT 0 COMMENT '是否促销单 0/1',
  region        VARCHAR(32)   DEFAULT NULL COMMENT '收货省份',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_id (order_id),
  KEY idx_order_time (order_time),
  KEY idx_category_time (category, order_time),
  KEY idx_user (user_id),
  KEY idx_pay_type (pay_type),
  KEY idx_promo_time (promo_flag, order_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE IF NOT EXISTS sales_target (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  period_type   VARCHAR(16)   NOT NULL COMMENT '周期类型，当前仅 month',
  period_value  VARCHAR(16)   NOT NULL COMMENT '周期值 yyyy-MM',
  category      VARCHAR(32)   NOT NULL COMMENT '品类，ALL 表示总目标',
  target_amount DECIMAL(14,2) NOT NULL COMMENT '目标销售额（元）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_period_category (period_type, period_value, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售目标表';

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

CREATE TABLE IF NOT EXISTS import_task_error (
  id        BIGINT       NOT NULL AUTO_INCREMENT,
  task_id   VARCHAR(64)  NOT NULL,
  row_no    INT          NOT NULL COMMENT 'CSV 行号（含表头，从 1 开始）',
  field     VARCHAR(64)  DEFAULT NULL COMMENT '出错字段',
  message   VARCHAR(255) NOT NULL COMMENT '错误原因',
  PRIMARY KEY (id),
  KEY idx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入错误行明细';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日预聚合（可选）';""", 7.5)

    h2(doc, "2.4 清洗规则")
    para(doc, "清洗由 tools/olist-etl.mjs 完成（零依赖 Node 脚本），每次运行都会输出 data/out/data-quality-report.md。"
              "主要规则与影响行数如下：")
    bullet(doc, "状态过滤：只保留 delivered / shipped / invoiced / processing / approved，剔除 canceled、unavailable 共 1,239 单。")
    bullet(doc, "时间过滤：只保留 2017-01-01 至 2017-12-31；Olist 的 2016 年只有 9—12 月且数据稀疏，剔除 53,824 单，"
                "保证趋势与同比有完整一年。")
    bullet(doc, "无明细订单：3 单没有订单明细，取不到价格与数量，直接剔除，不做填充。")
    bullet(doc, "缺失值与异常值：品类缺失归入“其他”；价格 <0.01 或 >200,000 判为异常（本批次为 0 条）；"
                "金额一律按“单价 × 数量”重算，不含运费，保证导入接口的容差 0.01 校验能通过。")
    bullet(doc, "重复与幂等：order_id 重编码后全局唯一；导入接口按文件 SHA-256 判重，同一文件在 PROCESSING 状态下再次上传返回 409。")
    bullet(doc, "币种与枚举：BRL→CNY 汇率 1.45；支付方式 credit_card/debit_card→card、boleto→alipay、voucher→wechat；"
                "地域按 customer_state 映射中国省份（演示用映射，报告中已声明）。")
    bullet(doc, "用户与目标：user_id 由 customer_unique_id 重编码，保证同一自然人多次购买可被识别，复购率与 RFM 才成立；"
                "sales_target 由实际 GMV 除以 0.75~1.15 的确定性因子反推，共 96 条（12 个月 × 8 个品类口径）。")
    para(doc, "行数损耗：99,441 − 日期过滤 53,824 − 状态不符 1,239 − 无明细 3 = 44,375 单，总销售额 ¥8,857,386.34，"
              "与入库后的 SELECT COUNT(*)、SUM(total_amount) 完全一致。")

    # ---------------- 三、接口设计 ----------------
    h1(doc, "三、接口设计")

    h2(doc, "3.1 接口清单")
    para(doc, "所有接口统一返回 Result<T>{code, message, data}，HTTP 200 且 code=0 表示成功；参数校验失败返回 400、"
              "资源不存在 404、重复导入 409、服务端异常 500，message 为可直接展示的中文提示。")
    table(doc,
          ["#", "方法", "路径", "作用", "入参", "返回结构", "成功码"],
          [
              ["A1", "GET", "/api/categories", "品类列表与商品数", "无", "Result<List<CategoryVO>>", "0"],
              ["A2", "GET", "/api/orders", "订单分页", "page,size,category,keyword,payType,userType,promoFlag,startDate,endDate,sort", "Result<PageResult<OrderVO>>", "0"],
              ["A3", "GET", "/api/orders/kpi", "KPI 汇总", "通用统计参数", "Result<KpiVO>", "0"],
              ["A4", "GET", "/api/orders/ranking", "商品排行", "rankBy,limit + 通用统计参数", "Result<List<RankingVO>>", "0"],
              ["A5", "GET", "/api/orders/stats", "订单统计", "granularity + 通用统计参数", "Result<OrdersStatsVO>", "0"],
              ["A6", "GET", "/api/orders/{orderId}", "订单详情", "orderId（路径）", "Result<OrderVO>", "0"],
              ["A7", "GET", "/api/orders/trend", "趋势与对比", "granularity,compareType + 通用统计参数", "Result<TrendVO>", "0"],
              ["A8", "GET", "/api/orders/price-distribution", "客单价分布", "通用统计参数", "Result<PriceDistributionVO>", "0"],
              ["A9", "GET", "/api/orders/category-contribution", "品类构成", "通用统计参数", "Result<CategoryContributionVO>", "0"],
              ["A10", "GET", "/api/orders/hourly", "24 小时分布", "通用统计参数", "Result<HourlyVO>", "0"],
              ["A11", "GET", "/api/orders/user-analysis", "新老客与支付", "通用统计参数", "Result<UserAnalysisVO>", "0"],
              ["A12", "GET", "/api/orders/export", "CSV 导出", "订单分页参数（不含 page/size）", "CSV 文件流（UTF-8 BOM）", "HTTP 200"],
              ["A13", "GET", "/api/targets", "月度目标列表", "periodType,periodValue,category", "Result<TargetListVO>", "0"],
              ["A14", "GET", "/api/orders/target-progress", "目标达成进度", "periodValue,category", "Result<TargetProgressVO>", "0"],
              ["A15", "GET", "/api/orders/campaign-effect", "促销活动效果", "startDate,endDate,category", "Result<CampaignEffectVO>", "0"],
              ["A16", "GET", "/api/users/rfm", "RFM 分层", "category,startDate,endDate", "Result<RfmVO>", "0"],
              ["A17", "GET", "/api/orders/region", "地域分布", "通用统计参数", "Result<RegionSalesVO>", "0"],
              ["A18", "POST", "/api/orders/import", "提交 CSV 导入", "file（multipart）,append", "Result<ImportSubmitDTO>", "0"],
              ["A19", "GET", "/api/orders/import/tasks/{taskId}", "查询导入进度", "taskId（路径）", "Result<ImportTaskVO>", "0"],
          ],
          [0.8, 1.1, 3.9, 2.4, 4.3, 3.3, 0.9], font_size=7.5, align_center_cols={0, 1, 6})

    h2(doc, "3.2 返回结构示例")
    para(doc, "以 A3 的 KPI 接口为例，真实响应（2017 全年、无筛选）如下；错误响应仅 code/message 不同，data 为 null：")
    code(doc, """GET /api/orders/kpi?startDate=2017-01-01&endDate=2017-12-31

{
  "code": 0,
  "message": "success",
  "data": {
    "totalSales": 8857386.34,
    "totalOrders": 44375,
    "avgOrderValue": 199.60,
    "hotProductCount": 16545,
    "totalUsers": 43030,
    "newUserRate": 0.9817,
    "repurchaseRate": 0.0284
  }
}

// 参数错误示例
{ "code": 400, "message": "startDate 格式必须为 yyyy-MM-dd", "data": null }""", 8)
    para(doc, "前端 utils/request.js 只在 code===0 时返回 data，其余按 400/404/409/500 映射中文提示，"
              "并对 3 秒内的重复提示做去重，请求超时 60 秒。")

    # ---------------- 四、看板设计 ----------------
    h1(doc, "四、看板设计")

    h2(doc, "4.1 KPI 指标")
    table(doc,
          ["#", "指标名", "计算口径", "单位", "对应接口"],
          [
              ["1", "总销售额", "SUM(total_amount)，按筛选条件与日期区间过滤", "元", "A3"],
              ["2", "订单总数", "COUNT(*)", "单", "A3"],
              ["3", "客单价", "SUM(total_amount) / COUNT(*)，保留 2 位小数", "元/单", "A3"],
              ["4", "热销商品数", "COUNT(DISTINCT product_name)", "个", "A3"],
              ["5", "用户数", "COUNT(DISTINCT user_id)", "人", "A3"],
              ["6", "新客率", "期内首次下单用户数 / 期内下单用户数", "%", "A3 / A11"],
              ["7", "复购率", "期内下单 ≥2 次的用户数 / 期内下单用户数", "%", "A3 / A11"],
          ],
          [0.9, 2.4, 7.3, 1.8, 4.1], font_size=8.5, align_center_cols={0, 3, 4})

    h2(doc, "4.2 图表清单")
    table(doc,
          ["#", "图表类型", "展示内容", "数据维度", "对应接口"],
          [
              ["1", "折线图", "销售额与订单量按日 / 周 / 月趋势", "时间 × 金额 / 单量", "A5 / A7"],
              ["2", "对比柱状图", "本期与上期（环比）或去年同期（同比）并排对比", "时间 × 金额", "A7"],
              ["3", "横向条形图", "商品销售额 / 销量 Top10", "商品 × 金额", "A4"],
              ["4", "帕累托组合图", "品类销售额柱状 + 累计占比折线，标注 80% 线", "品类 × 金额", "A9"],
              ["5", "环形图", "品类 / 支付方式占比", "维度 × 占比", "A9 / A11"],
              ["6", "仪表盘", "月度销售额目标达成率与剩余天数", "单值 + 进度", "A13 / A14"],
              ["7", "地图 / 排行榜", "各省份销售额，地图组件加载失败时降级为条形榜", "省份 × 金额", "A17"],
              ["8", "柱状图", "客单价区间分布（0-100、100-200 等 6 档）", "金额区间 × 单量", "A8"],
              ["9", "柱状图 / 热力条", "24 小时下单量与销售额分布", "小时 × 单量", "A10"],
              ["10", "RFM 分层图", "四类客群人数与销售额贡献，柱状 / 环形可切换", "客群 × 人数 / 金额", "A16"],
              ["11", "ROI 柱状图", "四个大促活动 ROI 与 1.0 盈亏平衡线", "活动 × 倍数", "A15"],
              ["12", "Sparkline 迷你趋势", "KPI 卡片内的 7 日走势，不占版面", "日期 × 金额", "A5"],
          ],
          [0.9, 2.6, 5.6, 3.6, 3.6], font_size=8.5, align_center_cols={0, 4})

    h2(doc, "4.3 交互说明")
    para(doc, "交互围绕“一处筛选、全屏联动”设计，共 6 类：")
    bullet(doc, "全局筛选：日期区间、品类、支付方式、用户类型、促销标记、订单号/商品名关键字，位于顶栏，所有页面共用。")
    bullet(doc, "筛选联动：条件写入 Pinia 的 dashboard store，同时 revision 加 1；所有图表与表格 watch revision，"
                "统一重新请求，保证一屏数据来自同一次查询、口径一致，避免前端“假过滤”。")
    bullet(doc, "维度切换：趋势图可切换日/周/月与环比、同比；商品排行可切换销售额/销量；RFM 可切换柱状/环形。")
    bullet(doc, "表格交互：订单表支持服务端分页（每页 10/20/50 条）、按时间/金额/单价/数量排序（字段走白名单）、"
                "点击行打开详情弹窗，属于“行下钻”。")
    bullet(doc, "导入与导出：导出直接下载 CSV；导入选择文件与“覆盖/追加”后返回任务 ID，前端每 1.5 秒轮询进度，"
                "结束后展示成功行数与失败行原因。")
    bullet(doc, "AI 问答：输入中文问题后以 SSE 方式依次收到 thinking、sql、answer、chart 事件，页面按事件类型逐步渲染，"
                "支持多轮追问（如“那环比呢”）。")

    # ---------------- 五、实现说明 ----------------
    h1(doc, "五、实现说明")

    h2(doc, "5.1 目录结构")
    code(doc, """ESDID/
├─ backend/springboot-ES/          Spring Boot 4.0.8 后端（Java 25）
│  ├─ src/main/java/com/heima/
│  │  ├─ controller/               5 个控制器，19 个接口
│  │  ├─ service/impl/             聚合统计、导入导出、目标、RFM
│  │  ├─ mapper/ + resources/mapper/*.xml    MyBatis 动态 SQL
│  │  ├─ domain/{dto,vo,entity}/   请求参数 / 返回结构 / 实体
│  │  ├─ common/ exception/ config/ util/   统一返回、异常、缓存、异步、CORS
│  │  └─ task/                     ImportTaskExecutor、DailySummaryJob
│  └─ src/main/resources/db/schema.sql      5 张表建表脚本
├─ frontend/                       Vue 3.5 + Vite 6 + ECharts 5.5 看板
│  ├─ src/views/                   DashboardIndex、AssistantView
│  ├─ src/components/{charts,layout}  12 个图表 + 侧栏 / 顶栏 / 筛选栏
│  ├─ src/store/dashboard.js       全局筛选与版本号（联动核心）
│  └─ mock/mock-server.js          纯前端演示用 Mock 服务
├─ agent/                          Python FastAPI + DeepSeek 的 Text-to-SQL
│  ├─ app/orchestrator.py          双 Agent 编排（生成 → 校验 → 执行 → 解读）
│  ├─ app/sql_safety.py            sqlglot 白名单、危险关键字、强制 LIMIT
│  └─ run_agent.py                 uvicorn 启动入口（127.0.0.1:8000）
├─ tools/olist-etl.mjs             零依赖 ETL：CSV → orders.csv / init-data.sql
├─ data/{olist,out}/               原始数据与产物、质量报告
├─ docs/                           需求、后端开发、ETL、运行说明、项目文档
└─ dist/                           打包产物（jar、nginx、agent.exe）""", 7.8)

    h2(doc, "5.2 关键实现")
    para(doc, "（1）动态条件聚合：同一段过滤条件被 8 个统计接口复用。把 where 条件抽成 <sql> 片段，"
              "参数用 #{} 预编译绑定，既避免每个接口重复写条件，也避免拼接 SQL 带来的注入风险；排序字段单独走白名单，"
              "只允许 orderTime / totalAmount / price / quantity 四列。")
    code(doc, """<!-- resources/mapper/OrdersMapper.xml -->
<sql id="statFilters">
  <if test="payType != null and payType != ''">AND pay_type = #{payType}</if>
  <if test="userType != null and userType != ''">AND user_type = #{userType}</if>
  <if test="promoFlag != null">AND promo_flag = #{promoFlag}</if>
</sql>

<select id="kpi" resultType="com.heima.domain.dto.KpiRow">
  SELECT
    COALESCE(SUM(total_amount), 0)                                AS totalSales,
    COUNT(*)                                                      AS totalOrders,
    COALESCE(ROUND(SUM(total_amount) / NULLIF(COUNT(*), 0), 2), 0) AS avgOrderValue,
    COUNT(DISTINCT product_name)                                  AS hotProductCount,
    COUNT(DISTINCT user_id)                                       AS totalUsers,
    COUNT(DISTINCT CASE WHEN user_type = 'new' THEN user_id END)   AS newUserCount
  FROM orders
  WHERE order_time &gt;= #{start} AND order_time &lt; DATE_ADD(#{end}, INTERVAL 1 DAY)
    <if test="category != null and category != ''">AND category = #{category}</if>
    <include refid="statFilters"/>
</select>""", 7.8)
    para(doc, "（2）异步 CSV 导入：上传接口只做“落任务 + 返回 taskId”，真正的逐行解析交给 @Async 线程池，"
              "避免大文件把请求线程占满；任务状态 PROCESSING → SUCCESS/PARTIAL/FAILED，错误行写入 import_task_error。"
              "数据写入放在独立事务里，任务状态在事务提交后再更新，这样即使导入失败回滚，用户也能在任务列表看到 FAILED 和原因。"
              "同一个文件用 SHA-256 判重，处理中重复上传直接 409。")
    code(doc, """// service/task/ImportTaskExecutor.java
@Async(AsyncConfig.IMPORT_EXECUTOR)
public void runAsync(String taskId, byte[] bytes, boolean append) {
    try {
        ImportResult result = importDataTxService.importCsv(taskId, bytes, append); // 独立事务
        taskService.finish(taskId, result.status(), result.validRows(), result.errorRows());
        taskErrorService.saveErrors(taskId, result.errors());   // 事务提交后写错误明细
        cacheService.evictCategoryCache();
    } catch (Exception e) {
        log.error("导入任务失败 taskId={}", taskId, e);
        taskService.finish(taskId, "FAILED", 0, 0, e.getMessage());
    }
}""", 7.8)
    para(doc, "（3）前端统一筛选：所有筛选条件集中在 Pinia，变更时 revision + 1；每个图表组件 watch 版本号后自行请求，"
              "组件之间不传参、不互相调用。这样新增一个图表时不需要改动任何已有组件，也天然避免了“筛完一半、另一半没刷”的问题。")
    code(doc, """// store/dashboard.js
state: () => ({
  startDate, endDate, category: '', payType: '', userType: '', promoFlag: '',
  keyword: '', revision: 0
}),
getters: {
  statParams: (state) => cleanParams({
    category: state.category, payType: state.payType, userType: state.userType,
    promoFlag: state.promoFlag, startDate: state.startDate, endDate: state.endDate
  })
},
actions: {
  applyFilters(patch) { this.$patch(patch); this.revision++ }   // 触发全量刷新
}

// 任一图表组件内
watch(() => dashboard.revision, () => loadData())""", 7.8)

    h2(doc, "5.3 技术难点与解决")
    table(doc,
          ["#", "难点", "解决方式", "是否借助 AI"],
          [
              ["1", "Olist 原始数据缺少商品名、品类枚举、支付方式枚举、目标数据", "写零依赖 ETL 做字段映射与构造，所有派生字段在质量报告中声明；"
                     "目标额用确定性因子反推，保证每次生成结果一致", "是（映射表与脚本骨架）"],
              ["2", "Spring Boot 4 与旧依赖冲突（druid、knife4j、pagehelper）", "去掉 druid 换 HikariCP；knife4j 换 springdoc 坐标；pagehelper 升到 4.1.1；"
                     "配置迁移到 Spring Boot 4 的 yml 结构", "是（报错定位与方案对比）"],
              ["3", "AI 生成的 SQL 可能越权、慢查询、写库", "sqlglot 解析为语法树：只允许单条 SELECT、表白名单、字段白名单、危险关键字拦截、强制 LIMIT 1000；"
                     "数据库使用只读账号", "是（白名单与校验规则）"],
              ["4", "本地连远端 MySQL 超时，13 个集成测试报 ApplicationContext 失败", "把配置连库改为本机建库导入 init-data.sql，去掉网络依赖后重跑；"
                     "确认是链路问题而非代码问题，surefire 报告归档", "否（人工排查）"],
          ],
          [0.9, 4.2, 8.2, 3.4], font_size=8.5, align_center_cols={0, 3})

    # ---------------- 六、测试记录 ----------------
    h1(doc, "六、测试记录")
    para(doc, "后端共 13 个测试类、96 个用例（JUnit 5 + Spring Boot Test），其中 83 个不依赖数据库；"
              "另外 Agent 侧 27 个 pytest 用例。以下为关键用例记录：")
    table(doc,
          ["#", "用例", "输入", "期望结果", "实际结果", "结论"],
          [
              ["1", "全量单元/集成测试", "mvn test", "96 个用例全部通过", "96/96，0 Failure / 0 Error", "通过"],
              ["2", "KPI 口径", "2017 全年无筛选查询 A3", "与 SQL 直查一致（8,857,386.34 元 / 44,375 单）", "一致", "通过"],
              ["3", "空区间", "startDate=2020-01-01&endDate=2020-01-02", "code=0，数据全 0 或空数组，不抛异常", "返回空集，无异常", "通过"],
              ["4", "末页与非法分页", "page=9999&size=100 / size=101", "末页返回空列表；size 超限返回 400", "符合预期", "通过"],
              ["5", "坏 CSV 导入", "4 行数据，其中 1 行单价 -1", "任务 PARTIAL，3 行成功，1 行返回行号与原因", "PARTIAL，错误行 4", "通过"],
              ["6", "重复导入", "同一文件处理中再次上传", "409 + “同一文件已有导入任务正在处理中”", "409，提示正确", "通过"],
              ["7", "非法参数", "startDate=2026-13-01", "400 + 中文格式提示", "400，提示正确", "通过"],
              ["8", "AI SQL 越权", "DELETE FROM orders / 查非白名单表", "被 sql_safety 拒绝，错误信息回喂模型重试", "拒绝并重试", "通过"],
          ],
          [0.9, 3.2, 4.4, 4.0, 3.2, 1.0], font_size=8, align_center_cols={0, 5})
    note(doc, "本地启动时曾出现 13 个 Error（dataSourceScriptDatabaseInitializer 连不上远端 MySQL），根因是配置指向的数据库"
               "网络不通，不是业务代码问题；改本机库后全量用例恢复 96/96。当前仓库的自动化测试仍需先启动 MySQL 才能完整跑通，"
               "已放入 7.2 改进项。")

    # ---------------- 七、总结与分工 ----------------
    h1(doc, "七、总结与分工")

    h2(doc, "7.1 团队分工与贡献")
    table(doc,
          ["成员", "分工", "主要工作量", "自评"],
          [
              ["杨程鹏（组长）", "整体规划 / 需求分析 / 文档统筹 / 数据工程 / 后端",
               "确定选题与范围、拆解任务与排期；Olist 数据清洗、字段映射与 ETL 脚本；MySQL 五表设计；"
               "Spring Boot 19 个接口、异步导入与流式导出；Agent 服务端接入与部署脚本；串联全部文档", "良"],
              ["陈梦飞", "前端开发 / 测试 / 可视化规范",
               "Vue 3 六页看板、12 个图表组件、Pinia 筛选联动；CSV 导入弹窗与订单下钻；"
               "可视化规范（配色/字号/空态）；96 个后端用例与 27 个 Agent 用例的执行与归档", "良"],
              ["朱邦煦", "设计规范 / 答辩材料",
               "六页界面视觉规范与原型走查；图表配色、字号与版式统一；答辩 PPT、讲稿与演示视频脚本；"
               "提交材料形式审查", "良"],
          ],
          [2.4, 3.6, 8.1, 2.6], font_size=8.5, align_center_cols={0, 3})

    h2(doc, "7.2 遇到的问题与改进方向")
    para(doc, "这一部分由小组共同整理，下面先记录真实遇到的问题，提交前会按自己的语言再改写一遍。")
    bullet(doc, "数据基线不统一：仓库里的 ETL 产物是 2017 年数据，而集成测试和部分文档示例按 2026 年写，"
                "测试在 2026 窗口查 2017 数据只会拿到空集，“通过”并不能证明统计正确。改进：统一时间轴后重新生成产物，"
                "重跑 96 个用例并归档 surefire 报告。")
    bullet(doc, "数据量未达标：入库 44,375 行，低于任务书 10 万行的要求。改进：补一张订单明细表（order_items），"
                "按“订单 × 明细行”入库即可超过 10 万行，同时让“件数/均价”类指标更真实。")
    bullet(doc, "品类归并偏粗：“其他”占订单 29%、销售额 32%，排第一，说明英文品类到六大类的映射覆盖不足。"
                "改进：按 Olist 的 category 翻译表细化映射，或直接在页面提供“原始品类”模式。")
    bullet(doc, "促销拉动率口径不一致：线上接口返回的是“促销销售额占比”，与文档中“活动期相对非活动期的提升”不是同一个东西。"
                "改进：以非活动期日均销售额为基准重算，前后端与文档一起改。")
    bullet(doc, "配置与凭据：数据库地址在 application-dev.yml、agent/.env 与旧文档中出现过三个不同的 IP，密码为明文。"
                "改进：统一到环境变量，仓库只保留 .env.example，并在 .gitignore 中排除真实配置。")
    para(doc, "如果重做一次，我会先把“数据口径、时间区间、目标定义”写成一份冻结的约定，再开始写接口和图表；"
              "这次最大的返工都来自口径反复，而不是技术难点。")

    out_dir = os.path.dirname(out_path)
    if out_dir and not os.path.isdir(out_dir):
        os.makedirs(out_dir, exist_ok=True)
    doc.save(out_path)
    print("saved", out_path)


if __name__ == "__main__":
    build(os.path.join("docs", "结课提交物", "02-项目文档-电商销售数据洞察看板.docx"))