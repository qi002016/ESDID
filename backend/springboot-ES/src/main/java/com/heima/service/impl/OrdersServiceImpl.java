package com.heima.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.heima.common.PageResult;
import com.heima.domain.dto.OrderQueryDTO;
import com.heima.domain.po.Orders;
import com.heima.domain.vo.OrderVO;
import com.heima.mapper.OrdersMapper;
import com.heima.service.IOrdersService;
import com.heima.util.CsvUtils;
import com.heima.util.DateRangeUtils;
import com.heima.util.MetricsUtils;
import com.heima.util.SqlSortWhitelist;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 订单基础查询与导出服务实现（A2 / A6 / A12）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrdersServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersService {

    /** 导出批次大小：内存中只保留一批评分数据 */
    private static final int EXPORT_BATCH_SIZE = 1000;

    /** CSV 的 UTF-8 BOM，Excel 打开中文不乱码 */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final OrdersMapper ordersMapper;

    @Override
    public PageResult<OrderVO> pageOrders(OrderQueryDTO query) {
        // 1. 日期缺省化（最近 30 天）与 start > end 校验
        String[] range = DateRangeUtils.normalize(query.getStartDate(), query.getEndDate());
        query.setStartDate(range[0]);
        query.setEndDate(range[1]);

        // 2. 排序白名单
        SqlSortWhitelist.Sort sort = SqlSortWhitelist.parseOrderSort(query.getSort());

        int page = query.getPage() == null ? 1 : query.getPage();
        int size = query.getSize() == null ? 10 : query.getSize();

        // 3. total 与 records 使用同一套条件（不做 count 优化，写法更可控）
        long total = ordersMapper.countOrders(query);
        if (total == 0) {
            return PageResult.empty(page, size);
        }
        int offset = (page - 1) * size;
        List<OrderVO> records = ordersMapper.pageOrders(query, sort.getColumn(), sort.getDirection(), offset, size);
        return new PageResult<>(total, page, size, records);
    }

    @Override
    public OrderVO getByOrderId(String orderId) {
        return ordersMapper.getByOrderId(orderId);
    }

    @Override
    public void exportCsv(OrderQueryDTO query, OutputStream outputStream) {
        // 日期缺省化，保证导出文件名与内容口径一致
        String[] range = DateRangeUtils.normalize(query.getStartDate(), query.getEndDate());
        query.setStartDate(range[0]);
        query.setEndDate(range[1]);

        try {
            outputStream.write(UTF8_BOM);
            try (Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                 BufferedWriter bufferedWriter = new BufferedWriter(writer)) {
                bufferedWriter.write(CsvUtils.HEADER);
                bufferedWriter.write('\n');

                long lastId = 0L;
                long exported = 0L;
                while (true) {
                    // 游标分页：id > lastId，避免 OFFSET 深翻页
                    List<Orders> batch = ordersMapper.exportBatch(query, lastId, EXPORT_BATCH_SIZE);
                    if (batch.isEmpty()) {
                        break;
                    }
                    for (Orders order : batch) {
                        bufferedWriter.write(MetricsUtils.toCsvLine(order));
                        bufferedWriter.write('\n');
                        lastId = order.getId();
                        exported++;
                    }
                    bufferedWriter.flush();
                }
                log.info("CSV 导出完成 range={}~{} rows={}", range[0], range[1], exported);
            }
        } catch (Exception e) {
            // 流式响应已开始写出，无法再改状态码，只能记录日志
            log.error("CSV 导出失败 range={}~{}", range[0], range[1], e);
        }
    }
}