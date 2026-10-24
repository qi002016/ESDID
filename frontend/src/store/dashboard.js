import { defineStore } from 'pinia'
import { recentRange, currentMonth } from '@/utils/format'
import { cleanParams } from '@/utils/request'

/** 默认时间窗口：近 30 天（接口文档缺省值一致） */
const DEFAULT_DAYS = 30

/**
 * 全历史开区间：顶栏按订单号 / 商品名检索时，不落入后端默认的近 30 天窗口。
 * 后端 DateRangeUtils 对非空日期原样透传，mapper 用 order_time >= start AND < DATE_ADD(end, 1day) 过滤，
 * 因此传一个覆盖全部历史的开区间即可实现“搜得到任意时间下单的商品/订单”，且无需改动后端 jar。
 * 注意：上界不能用 9999-12-31，mapper 会对 end 再加一天，9999 溢出为 NULL 会使条件永假；
 * 2999-12-31 加一天仍合法，且远超任何业务数据。
 */
const FULL_RANGE_START = '1900-01-01'
const FULL_RANGE_END = '2999-12-31'

/**
 * 全局筛选条件 store
 * 设计要点：
 *  1. 所有筛选参数集中在此，组件之间不传参；
 *  2. revision 为筛选版本号，筛选变更时 +1，所有图表/表格 watch 它即可统一刷新；
 *  3. 提供 3 组参数派生 getter，严格按接口文档控制每个接口的参数集合。
 */
export const useDashboardStore = defineStore('dashboard', {
  state: () => {
    const [startDate, endDate] = recentRange(DEFAULT_DAYS)
    return {
      // ---- 全局筛选（A2/A3/A4/A5/A7/A8/A10/A11/A12/A16/A17 共用）----
      startDate, // yyyy-MM-dd
      endDate, // yyyy-MM-dd
      category: '', // 品类，'' 表示全部
      payType: '', // 支付方式 alipay/wechat/card
      userType: '', // 用户类型 new/old
      promoFlag: '', // 促销标记 0/1
      keyword: '', // 订单号或商品名模糊匹配

      // ---- 各图表的局部视图状态（也集中管理，便于跨组件联动）----
      granularity: 'day', // A5/A7 聚合粒度 day/week/month
      trendMode: 'normal', // 趋势图模式：normal 普通趋势 / compare 对比趋势
      compareType: 'previous', // A7 对比方式 previous 环比 / previousYear 同比
      rankBy: 'sales', // A4 排序口径 sales/quantity
      rfmChartType: 'bar', // A16 展示形态 bar/pie
      periodValue: currentMonth(), // A13/A14 目标周期 yyyy-MM

      // ---- 订单表格分页状态 ----
      orderPage: 1,
      orderSize: 10,
      orderSort: 'orderTime,desc',

      // ---- 筛选版本号：变更后触发全量刷新 ----
      revision: 0
    }
  },

  getters: {
    /**
     * 统计类接口参数（A3/A4/A5/A7/A8/A10/A11/A16/A17）
     *
     * 注意：接口文档中这些接口仅声明了 category / startDate / endDate。
     * 但筛选栏里的「支付方式 / 用户类型 / 促销标记」与品类同属全局筛选，
     * 若不透传，切换后只是用同样的参数重新请求一次，图表数据不会变化
     *（表现为「点了没反应」）。因此这里统一透传这三个维度：
     * 后端若未实现对应过滤，忽略未知参数即可，不影响兼容性。
     */
    statParams(state) {
      return cleanParams({
        category: state.category,
        payType: state.payType,
        userType: state.userType,
        promoFlag: state.promoFlag,
        startDate: state.startDate,
        endDate: state.endDate
      })
    },

    /**
     * 订单分页参数（A2）
     * page/size/category/keyword/payType/userType/promoFlag/startDate/endDate/sort
     *
     * 顶栏 keyword（订单号 / 商品名）检索属于“全局查找”，不受所选时间窗约束：
     * 只要该商品 / 订单存在，无论下单时间都应能查到。此时改传全历史开区间，
     * 其余品类 / 支付 / 用户 / 促销维度仍然生效；清空关键字后恢复所选时间窗。
     */
    orderParams(state) {
      const searching = Boolean(state.keyword && state.keyword.trim())
      return cleanParams({
        page: state.orderPage,
        size: state.orderSize,
        category: state.category,
        keyword: state.keyword,
        payType: state.payType,
        userType: state.userType,
        promoFlag: state.promoFlag,
        startDate: searching ? FULL_RANGE_START : state.startDate,
        endDate: searching ? FULL_RANGE_END : state.endDate,
        sort: state.orderSort
      })
    },

    /**
     * CSV 导出参数（A12）
     * category/keyword/payType/userType/startDate/endDate
     * 与订单明细保持一致：keyword 检索时同样放开时间范围，导出的就是全量命中结果。
     */
    exportParams(state) {
      const searching = Boolean(state.keyword && state.keyword.trim())
      return cleanParams({
        category: state.category,
        keyword: state.keyword,
        payType: state.payType,
        userType: state.userType,
        startDate: searching ? FULL_RANGE_START : state.startDate,
        endDate: searching ? FULL_RANGE_END : state.endDate
      })
    },

    /** 筛选条件摘要文案，用于图表卡片副标题展示 */
    filterSummary(state) {
      const parts = []
      // keyword 全局检索不受时间窗约束，副标题如实标注“全时段”
      if (state.keyword && state.keyword.trim()) {
        parts.push('全时段检索')
      } else {
        parts.push(`${state.startDate} ~ ${state.endDate}`)
      }
      parts.push(state.category ? `品类：${state.category}` : '全部品类')
      if (state.payType) parts.push(`支付：${state.payType}`)
      if (state.userType) parts.push(`用户：${state.userType}`)
      return parts.join(' · ')
    }
  },

  actions: {
    /** 更新筛选条件（FilterBar 统一调用），并 bump 版本号触发全量刷新 */
    setFilters(patch = {}) {
      Object.assign(this, patch)
      this.orderPage = 1 // 筛选变化回到第一页
      this.revision++
    },

    /** 快捷时间范围：近 7 / 30 / 90 天 */
    setDateRange(days) {
      const [startDate, endDate] = recentRange(days)
      this.setFilters({ startDate, endDate })
    },

    /** 重置为默认：近 30 天 + 清空维度筛选 */
    resetFilters() {
      const [startDate, endDate] = recentRange(DEFAULT_DAYS)
      Object.assign(this, {
        startDate,
        endDate,
        category: '',
        payType: '',
        userType: '',
        promoFlag: '',
        keyword: '',
        granularity: 'day',
        trendMode: 'normal',
        compareType: 'previous',
        rankBy: 'sales',
        orderPage: 1,
        orderSize: 10,
        orderSort: 'orderTime,desc',
        periodValue: currentMonth()
      })
      this.revision++
    },

    /**
     * 订单表格分页/排序变化
     * 注意：不 bump revision，避免翻页时把全部图表也重新请求一遍
     *（表格自身 watch orderParams 触发刷新）
     */
    setOrderQuery(patch = {}) {
      Object.assign(this, patch)
    }
  }
})
