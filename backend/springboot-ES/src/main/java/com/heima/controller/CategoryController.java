package com.heima.controller;

import com.heima.common.Result;
import com.heima.domain.vo.CategoryVO;
import com.heima.service.ICategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A1 品类列表。
 */
@Tag(name = "基础数据", description = "A1 品类列表")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final ICategoryService categoryService;

    @Operation(summary = "品类列表（空库返回空数组）")
    @GetMapping
    public Result<List<CategoryVO>> categories() {
        return Result.ok(categoryService.listCategories());
    }
}