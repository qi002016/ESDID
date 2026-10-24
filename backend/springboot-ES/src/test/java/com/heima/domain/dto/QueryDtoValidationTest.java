package com.heima.domain.dto;

import com.heima.common.Result;
import com.heima.exception.GlobalExceptionHandler;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询 DTO 的 Jakarta Bean Validation 约束测试（《后端开发文档》§2.3 参数校验约定）。
 * <p>这些约束由 {@code @Valid} 在 Controller 层触发，违规时经 {@link GlobalExceptionHandler} 统一返回 400。</p>
 */
class QueryDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    private static List<String> violationFields(Object bean) {
        Set<ConstraintViolation<Object>> violations = validator.validate(bean);
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .sorted()
                .toList();
    }

    // ==================== granularity ====================

    @Test
    @DisplayName("granularity 只接受 day|week|month，其余 -> 校验失败（400）")
    void statsQuery_granularityRejectsUnknownValue() {
        StatsQueryDTO query = new StatsQueryDTO();
        query.setGranularity("hour");
        assertThat(violationFields(query)).containsExactly("granularity");

        query.setGranularity("day");
        assertThat(violationFields(query)).isEmpty();
        query.setGranularity("week");
        assertThat(violationFields(query)).isEmpty();
        query.setGranularity("month");
        assertThat(violationFields(query)).isEmpty();
        // 缺省 = day，因此允许为空
        query.setGranularity(null);
        assertThat(violationFields(query)).isEmpty();
    }

    @Test
    @DisplayName("TrendQueryDTO.granularity 同样只接受 day|week|month")
    void trendQuery_granularityRejectsUnknownValue() {
        TrendQueryDTO query = new TrendQueryDTO();
        query.setGranularity("quarter");
        assertThat(violationFields(query)).containsExactly("granularity");
    }

    // ==================== compareType ====================

    @Test
    @DisplayName("compareType 只接受 previous|previousYear，其余 -> 校验失败（400）")
    void trendQuery_compareTypeRejectsUnknownValue() {
        TrendQueryDTO query = new TrendQueryDTO();
        query.setCompareType("lastMonth");
        assertThat(violationFields(query)).containsExactly("compareType");

        query.setCompareType("previous");
        assertThat(violationFields(query)).isEmpty();
        query.setCompareType("previousYear");
        assertThat(violationFields(query)).isEmpty();
        // 缺省 = previous，因此允许为空
        query.setCompareType(null);
        assertThat(violationFields(query)).isEmpty();
    }

    // ==================== 日期与周期 ====================

    @Test
    @DisplayName("startDate/endDate 必须是 yyyy-MM-dd")
    void statsQuery_datePattern() {
        StatsQueryDTO query = new StatsQueryDTO();
        query.setStartDate("2026/09/01");
        query.setEndDate("20260930");
        assertThat(violationFields(query)).containsExactly("endDate", "startDate");

        query.setStartDate("2026-09-01");
        query.setEndDate("2026-09-30");
        assertThat(violationFields(query)).isEmpty();
    }

    @Test
    @DisplayName("periodType 仅 month、periodValue 必须 yyyy-MM")
    void targetQuery_periodPatterns() {
        TargetQueryDTO query = new TargetQueryDTO();
        query.setPeriodType("week");
        query.setPeriodValue("2026/09");
        assertThat(violationFields(query)).containsExactly("periodType", "periodValue");

        query.setPeriodType("month");
        query.setPeriodValue("2026-09");
        assertThat(violationFields(query)).isEmpty();

        query.setPeriodType(null);
        query.setPeriodValue(null);
        assertThat(violationFields(query)).isEmpty();
    }

    // ==================== 校验失败 -> 400 ====================

    @Test
    @DisplayName("@Valid 校验失败经 GlobalExceptionHandler 统一返回 code=400")
    void bindFailure_becomes400Response() throws Exception {
        BindException bindException = new BindException(new TrendQueryDTO(), "query");
        bindException.addError(new FieldError("query", "compareType", "lastMonth", false, null, null,
                "compareType 仅支持 previous/previousYear"));

        Result<Void> body = new GlobalExceptionHandler().handleValid(bindException);

        assertThat(body.getCode()).isEqualTo(400);
        assertThat(body.getMessage()).contains("compareType");
        assertThat(body.getData()).isNull();
    }
}