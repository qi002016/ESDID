package com.heima.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.heima.common.PageResult;
import com.heima.domain.dto.OrderQueryDTO;
import com.heima.domain.po.Orders;
import com.heima.domain.vo.OrderVO;

import java.io.OutputStream;

/**
 * 订单基础查询与导出服务（A2 / A6 / A12）。
 */
public interface IOrdersService extends IService<Orders> {

    /** A2 订单分页查询（count + limit，排序走白名单） */
    PageResult<OrderVO> pageOrders(OrderQueryDTO query);

    /** A6 订单详情，不存在返回 null（由 Controller 转 404） */
    OrderVO getByOrderId(String orderId);

    /** A12 流式导出 CSV（游标分页，避免 OOM） */
    void exportCsv(OrderQueryDTO query, OutputStream outputStream);
}