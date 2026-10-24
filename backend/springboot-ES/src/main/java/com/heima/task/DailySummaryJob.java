package com.heima.task;

import com.heima.mapper.DailySummaryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * P1 预聚合任务（《后端开发文档》§9.4）：每日把前一天的订单汇总进 {@code daily_summary}。
 * <p>支持传入日期补跑（{@link #rebuild(LocalDate)}）。当前统计接口仍直接读订单表，
 * 保证「当天数据也能立即反映」，预聚合表用于后续扩展与容量优化。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySummaryJob {

    private final DailySummaryMapper dailySummaryMapper;

    /** 每天 01:30 汇总前一天数据 */
    @Scheduled(cron = "${dashboard.schedule.daily-summary-cron}")
    public void run() {
        rebuild(LocalDate.now().minusDays(1));
    }

    /** 汇总指定日期（先删后插，可重复执行） */
    public int rebuild(LocalDate date) {
        dailySummaryMapper.deleteByDate(date);
        int rows = dailySummaryMapper.insertFromOrders(date);
        log.info("预聚合完成 statDate={} rows={}", date, rows);
        return rows;
    }
}