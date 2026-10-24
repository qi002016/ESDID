package com.heima.controller;

import com.heima.common.Result;
import com.heima.domain.dto.TargetQueryDTO;
import com.heima.domain.vo.TargetListVO;
import com.heima.service.ISalesTargetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A13 销售目标列表。
 */
@Tag(name = "销售目标", description = "A13 销售目标列表")
@RestController
@RequestMapping("/api/targets")
@RequiredArgsConstructor
public class SalesTargetController {

    private final ISalesTargetService salesTargetService;

    @Operation(summary = "A13 销售目标列表（缺省当前月；目标表为空返回空数组）")
    @GetMapping
    public Result<TargetListVO> listTargets(@Valid TargetQueryDTO query) {
        return Result.ok(salesTargetService.listTargets(query));
    }
}