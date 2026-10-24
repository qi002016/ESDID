package com.heima.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.heima.domain.dto.CategorySalesRow;
import com.heima.domain.dto.TargetQueryDTO;
import com.heima.domain.vo.TargetListVO;
import com.heima.domain.vo.TargetProgressVO;
import com.heima.mapper.OrdersMapper;
import com.heima.mapper.SalesTargetMapper;
import com.heima.service.impl.SalesTargetServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SalesTargetServiceImpl} 单元测试（A13/A14）。
 * <p>覆盖《后端开发文档》§10.1 的「A14 目标缺失」「A14 周期已结束」用例；
 * 周期一律取过去月份，因此 remainingDays 与时钟无关（恒为 0）。</p>
 */
@ExtendWith(MockitoExtension.class)
class SalesTargetServiceImplTest {

    @Mock
    private SalesTargetMapper salesTargetMapper;

    @Mock
    private OrdersMapper ordersMapper;

    /** 用真实 Caffeine 实例构造（与 CacheConfig 中的 Bean 同类型），便于同时验证 A13 缓存行为 */
    private Cache<String, TargetListVO> targetListCache;

    private SalesTargetServiceImpl service;

    @BeforeEach
    void setUp() {
        targetListCache = Caffeine.<String, TargetListVO>newBuilder().maximumSize(16).build();
        service = new SalesTargetServiceImpl(salesTargetMapper, ordersMapper, targetListCache);
    }

    // ==================== 工具方法 ====================

    private static TargetQueryDTO targetQuery(String periodType, String periodValue, String category) {
        TargetQueryDTO query = new TargetQueryDTO();
        query.setPeriodType(periodType);
        query.setPeriodValue(periodValue);
        query.setCategory(category);
        return query;
    }

    private static TargetListVO.TargetVO target(String category, String amount) {
        return TargetListVO.TargetVO.builder()
                .category(category)
                .targetAmount(new BigDecimal(amount))
                .build();
    }

    private static CategorySalesRow achieved(String category, String amount) {
        CategorySalesRow row = new CategorySalesRow();
        row.setCategory(category);
        row.setAchieved(new BigDecimal(amount));
        return row;
    }

    // ==================== A13 目标列表（缓存） ====================

    @Test
    @DisplayName("A13 目标列表命中 Caffeine 缓存：同一 periodValue 只查一次库")
    void listTargets_secondCallHitsCache() {
        List<TargetListVO.TargetVO> items = List.of(target("ALL", "200000.00"), target("数码", "120000.00"));
        when(salesTargetMapper.listTargets("month", "2025-03", "数码")).thenReturn(items);

        TargetListVO first = service.listTargets(targetQuery("month", "2025-03", "数码"));
        TargetListVO second = service.listTargets(targetQuery(null, "2025-03", "数码"));

        assertThat(first.getPeriodType()).isEqualTo("month");       // periodType 缺省 = month
        assertThat(second.getItems()).isSameAs(items);
        verify(salesTargetMapper, times(1)).listTargets("month", "2025-03", "数码");
    }

    @Test
    @DisplayName("A13 evictTargetCache 后重新查库（导入完成主动失效）")
    void evictTargetCache_forcesReload() {
        List<TargetListVO.TargetVO> items = List.of(target("ALL", "200000.00"));
        when(salesTargetMapper.listTargets(eq("month"), eq("2025-03"), isNull())).thenReturn(items);

        service.listTargets(targetQuery("month", "2025-03", null));
        service.evictTargetCache();
        service.listTargets(targetQuery("month", "2025-03", null));

        verify(salesTargetMapper, times(2)).listTargets(eq("month"), eq("2025-03"), isNull());
    }

    // ==================== A14 目标缺失 ====================

    @Test
    @DisplayName("A14 目标缺失：achievementRate=null、dailyNeeded=0（targetAmount=0，不假造 0%）")
    void progress_targetMissing_achievementRateNullAndDailyNeededZero() {
        // 过去月份，与时钟无关
        when(ordersMapper.achievedByCategory("2025-03-01", "2025-03-31"))
                .thenReturn(List.of(achieved("数码", "5000.00"), achieved("家电", "3000.00")));
        // sales_target 里没有「数码」
        when(salesTargetMapper.listTargets("month", "2025-03", "数码")).thenReturn(List.of());

        TargetProgressVO vo = service.progress(targetQuery("month", "2025-03", "数码"));

        assertThat(vo.getTotalTarget()).isEqualByComparingTo("0.00");
        assertThat(vo.getAchievedSales()).isEqualByComparingTo("5000.00");
        assertThat(vo.getAchievementRate()).isNull();
        assertThat(vo.getDailyNeeded()).isEqualByComparingTo("0");
        assertThat(vo.getRemainingDays()).isZero();

        assertThat(vo.getItems()).hasSize(1);
        TargetProgressVO.TargetProgressItemVO item = vo.getItems().get(0);
        assertThat(item.getCategory()).isEqualTo("数码");
        assertThat(item.getTargetAmount()).isEqualByComparingTo("0.00");
        assertThat(item.getAchieved()).isEqualByComparingTo("5000.00");
        assertThat(item.getAchievementRate()).isNull();
    }

    @Test
    @DisplayName("A14 目标缺失且周期未结束（当前月）：dailyNeeded 仍为 0（gap<=0 时也返回 0）")
    void progress_targetMissingInCurrentMonth_dailyNeededStillZero() {
        String currentMonth = YearMonth.now().toString();          // 结果与具体日期无关
        when(ordersMapper.achievedByCategory(any(), any())).thenReturn(List.of(achieved("数码", "5000.00")));
        when(salesTargetMapper.listTargets("month", currentMonth, "数码")).thenReturn(List.of());

        TargetProgressVO vo = service.progress(targetQuery("month", currentMonth, "数码"));

        assertThat(vo.getAchievementRate()).isNull();
        assertThat(vo.getDailyNeeded()).isEqualByComparingTo("0");
        assertThat(vo.getTotalTarget()).isEqualByComparingTo("0.00");
    }

    // ==================== A14 周期已结束 ====================

    @Test
    @DisplayName("A14 周期已结束：remainingDays=0、dailyNeeded=0，但达成率照常计算")
    void progress_periodEnded_remainingDaysAndDailyNeededZero() {
        when(ordersMapper.achievedByCategory("2025-03-01", "2025-03-31"))
                .thenReturn(List.of(achieved("数码", "60000.00"), achieved("家电", "40000.00")));
        when(salesTargetMapper.listTargets("month", "2025-03", null))
                .thenReturn(List.of(target("ALL", "200000.00"), target("数码", "120000.00")));

        TargetProgressVO vo = service.progress(targetQuery("month", "2025-03", null));

        assertThat(vo.getRemainingDays()).isZero();
        assertThat(vo.getDailyNeeded()).isEqualByComparingTo("0");
        assertThat(vo.getTotalTarget()).isEqualByComparingTo("200000.00");
        assertThat(vo.getAchievedSales()).isEqualByComparingTo("100000.00");
        assertThat(vo.getAchievementRate()).isEqualByComparingTo("0.5000");

        // items 必须同时包含 ALL 与 sales_target 里的每个品类
        assertThat(vo.getItems()).hasSize(2);
        assertThat(vo.getItems()).extracting(TargetProgressVO.TargetProgressItemVO::getCategory)
                .containsExactly("ALL", "数码");
        assertThat(vo.getItems().get(0).getTargetAmount()).isEqualByComparingTo("200000.00");
        assertThat(vo.getItems().get(0).getAchieved()).isEqualByComparingTo("100000.00");
        assertThat(vo.getItems().get(0).getAchievementRate()).isEqualByComparingTo("0.5000");
        assertThat(vo.getItems().get(1).getTargetAmount()).isEqualByComparingTo("120000.00");
        assertThat(vo.getItems().get(1).getAchieved()).isEqualByComparingTo("60000.00");
        assertThat(vo.getItems().get(1).getAchievementRate()).isEqualByComparingTo("0.5000");
    }

    @Test
    @DisplayName("A14 指定 category=ALL 时用全品类达成额合计")
    void progress_categoryAll_usesAllCategoryAchieved() {
        when(ordersMapper.achievedByCategory("2025-01-01", "2025-01-31"))
                .thenReturn(List.of(achieved("数码", "60000.00"), achieved("家电", "40000.00")));
        when(salesTargetMapper.listTargets("month", "2025-01", "ALL"))
                .thenReturn(List.of(target("ALL", "200000.00")));

        TargetProgressVO vo = service.progress(targetQuery("month", "2025-01", "ALL"));

        assertThat(vo.getAchievedSales()).isEqualByComparingTo("100000.00");
        assertThat(vo.getAchievementRate()).isEqualByComparingTo("0.5000");
        assertThat(vo.getItems()).hasSize(1);
        assertThat(vo.getItems().get(0).getCategory()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("A14 periodValue 缺省 = 当前月（仅验证与当前月一致的行为，不断言具体天数）")
    void progress_periodValueDefaultsToCurrentMonth() {
        String currentMonth = YearMonth.now().toString();
        when(ordersMapper.achievedByCategory(eq(currentMonth + "-01"), any())).thenReturn(List.of());
        when(salesTargetMapper.listTargets(eq("month"), eq(currentMonth), isNull())).thenReturn(List.of());

        TargetProgressVO vo = service.progress(targetQuery("month", null, null));

        assertThat(vo.getPeriodValue()).isEqualTo(currentMonth);
        assertThat(vo.getPeriodType()).isEqualTo("month");
        assertThat(vo.getAchievementRate()).isNull();
    }
}