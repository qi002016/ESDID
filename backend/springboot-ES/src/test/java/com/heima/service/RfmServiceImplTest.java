package com.heima.service;

import com.heima.domain.dto.RfmQueryDTO;
import com.heima.domain.dto.RfmUserRow;
import com.heima.domain.vo.RfmVO;
import com.heima.mapper.OrdersMapper;
import com.heima.service.impl.RfmServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RfmServiceImpl} 单元测试（A16 RFM 用户分层）。
 * <p>覆盖《后端开发文档》§10.1 的 A16 用例：阈值 = 中位数、8 类名称映射正确、salesRatio 之和 ≈ 1。</p>
 */
@ExtendWith(MockitoExtension.class)
class RfmServiceImplTest {

    private static final String START = "2026-01-01";
    private static final String END = "2026-03-31";

    @Mock
    private OrdersMapper ordersMapper;

    @InjectMocks
    private RfmServiceImpl service;

    // ==================== 工具方法 ====================

    private static RfmQueryDTO rfmQuery(String start, String end) {
        RfmQueryDTO query = new RfmQueryDTO();
        query.setStartDate(start);
        query.setEndDate(end);
        return query;
    }

    private static RfmUserRow user(String userId, int recencyDays, int frequency, String monetary) {
        RfmUserRow row = new RfmUserRow();
        row.setUserId(userId);
        row.setRecencyDays(recencyDays);
        row.setFrequency(frequency);
        row.setMonetary(new BigDecimal(monetary));
        return row;
    }

    /**
     * 8 个用户，恰好覆盖 R/F/M 的全部 8 种组合，且每个组合的 monetary 唯一，
     * 因此可以按「销售额降序」逐一断言 key -> 分层名称 的映射。
     * <pre>
     * R 中位数 = (1+10)/2 = 5  -> recency 1 记 1，10 记 0
     * F 中位数 = (1+5)/2  = 3  -> frequency 5 记 1，1 记 0
     * M 中位数 = (100+400)/2 = 250 -> monetary >= 250 记 1
     * </pre>
     */
    private static List<RfmUserRow> eightUsers() {
        return List.of(
                user("U_111", 1, 5, "1000.00"),
                user("U_110", 1, 5, "100.00"),
                user("U_101", 1, 1, "800.00"),
                user("U_100", 1, 1, "80.00"),
                user("U_011", 10, 5, "600.00"),
                user("U_010", 10, 5, "60.00"),
                user("U_001", 10, 1, "400.00"),
                user("U_000", 10, 1, "40.00"));
    }

    // ==================== A16 主用例 ====================

    @Test
    @DisplayName("A16 阈值 = 中位数（R/F/M），8 类名称映射正确，salesRatio 之和 ≈ 1")
    void analyze_thresholdsAreMedians_segmentNamesAndRatioSumAreCorrect() {
        when(ordersMapper.rfmUsers(START, END, null, END)).thenReturn(eightUsers());

        RfmVO vo = service.analyze(rfmQuery(START, END));

        // 阈值 = 中位数（R 中位数 5、F 中位数 3、M 中位数 250.00）
        assertThat(vo.getThresholds().getRDays()).isEqualTo(5);
        assertThat(vo.getThresholds().getFCount()).isEqualTo(3);
        assertThat(vo.getThresholds().getMAmount()).isEqualByComparingTo("250.00");

        assertThat(vo.getWindowStart()).isEqualTo(START);
        assertThat(vo.getWindowEnd()).isEqualTo(END);
        assertThat(vo.getTotalUsers()).isEqualTo(8);
        assertThat(vo.getTotalSales()).isEqualByComparingTo("3080.00");

        // segments 按 sales 降序；每个 key 一个用户、monetary 唯一 => 可精确断言 key -> 名称
        String[] expectedNames = {
                "重要价值客户",   // 111
                "重要发展客户",   // 101
                "重要保持客户",   // 011
                "重要挽留客户",   // 001
                "一般价值客户",   // 110
                "一般发展客户",   // 100
                "一般保持客户",   // 010
                "流失客户"        // 000
        };
        String[] expectedSales = {
                "1000.00", "800.00", "600.00", "400.00", "100.00", "80.00", "60.00", "40.00"
        };

        assertThat(vo.getSegments()).hasSize(8);
        List<String> actualNames = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            RfmVO.RfmSegmentVO segment = vo.getSegments().get(i);
            actualNames.add(segment.getSegment());
            assertThat(segment.getSales())
                    .as("第 %d 个分层的销售额", i)
                    .isEqualByComparingTo(expectedSales[i]);
            assertThat(segment.getUserCount()).isEqualTo(1);
        }
        assertThat(actualNames).containsExactly(expectedNames);
        assertThat(actualNames).doesNotContain("其他");

        // avgFrequency：111 的用户 frequency = 5
        assertThat(vo.getSegments().get(0).getAvgFrequency()).isEqualByComparingTo("5.00");

        // salesRatio 之和 ≈ 1
        BigDecimal ratioSum = vo.getSegments().stream()
                .map(RfmVO.RfmSegmentVO::getSalesRatio)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(ratioSum.doubleValue()).isCloseTo(1.0, within(0.01));
    }

    @Test
    @DisplayName("A16 奇数个用户：阈值取中位数而不是均值（极值不拉偏阈值）")
    void analyze_oddUserCount_thresholdIsMedianNotMean() {
        // recency 中位数 5 / 均值 35.3；frequency 中位数 2 / 均值 34.3；monetary 中位数 20.00 / 均值 343.33
        List<RfmUserRow> users = List.of(
                user("U_100", 1, 1, "10.00"),
                user("U_111", 5, 2, "20.00"),
                user("U_011", 100, 100, "1000.00"));
        when(ordersMapper.rfmUsers(START, END, null, END)).thenReturn(users);

        RfmVO vo = service.analyze(rfmQuery(START, END));

        assertThat(vo.getThresholds().getRDays()).isEqualTo(5);
        assertThat(vo.getThresholds().getFCount()).isEqualTo(2);
        assertThat(vo.getThresholds().getMAmount()).isEqualByComparingTo("20.00");
        assertThat(vo.getTotalSales()).isEqualByComparingTo("1030.00");
        assertThat(vo.getSegments()).extracting(RfmVO.RfmSegmentVO::getSegment)
                .containsExactly("重要保持客户", "重要价值客户", "一般发展客户");
    }

    @Test
    @DisplayName("A16 无用户：segments=[]、thresholds=null、totalUsers=0、totalSales=0.00")
    void analyze_emptyUsers_returnsEmptySegmentsAndNullThresholds() {
        when(ordersMapper.rfmUsers(START, END, null, END)).thenReturn(List.of());

        RfmVO vo = service.analyze(rfmQuery(START, END));

        assertThat(vo.getSegments()).isEmpty();
        assertThat(vo.getThresholds()).isNull();
        assertThat(vo.getTotalUsers()).isZero();
        assertThat(vo.getTotalSales()).isEqualByComparingTo("0.00");
        assertThat(vo.getWindowStart()).isEqualTo(START);
        assertThat(vo.getWindowEnd()).isEqualTo(END);
    }

    @Test
    @DisplayName("A16 日期缺省 = 最近 90 天（窗口结束日同时作为 recency 计算基准）")
    void analyze_defaultWindow_is90Days() {
        when(ordersMapper.rfmUsers(any(), any(), isNull(), any())).thenReturn(List.of());

        RfmVO vo = service.analyze(rfmQuery(null, null));

        ArgumentCaptor<String> startCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> endCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> windowEndCaptor = ArgumentCaptor.forClass(String.class);
        verify(ordersMapper).rfmUsers(startCaptor.capture(), endCaptor.capture(), isNull(), windowEndCaptor.capture());

        long windowDays = ChronoUnit.DAYS.between(
                LocalDate.parse(startCaptor.getValue()), LocalDate.parse(endCaptor.getValue())) + 1;
        assertThat(windowDays).isEqualTo(90);
        // windowEnd 必须与 endDate 一致
        assertThat(windowEndCaptor.getValue()).isEqualTo(endCaptor.getValue());
        assertThat(vo.getWindowEnd()).isEqualTo(endCaptor.getValue());
    }
}