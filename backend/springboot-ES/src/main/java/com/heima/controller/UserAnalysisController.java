package com.heima.controller;

import com.heima.common.Result;
import com.heima.domain.dto.RfmQueryDTO;
import com.heima.domain.vo.RfmVO;
import com.heima.service.IRfmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A16 RFM 用户分层。
 */
@Tag(name = "用户分析", description = "A16 RFM 用户分层")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserAnalysisController {

    private final IRfmService rfmService;

    @Operation(summary = "A16 RFM 用户分层（缺省最近 90 天，中位数阈值，8 类分层）")
    @GetMapping("/rfm")
    public Result<RfmVO> rfm(@Valid RfmQueryDTO query) {
        return Result.ok(rfmService.analyze(query));
    }
}