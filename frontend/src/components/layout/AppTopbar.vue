<template>
  <!-- 顶部导航：页面标题（左）；搜索、通知、头像（右）。日期筛选已并入 FilterBar -->
  <header class="topbar">
    <div class="topbar__left">
      <h2 class="topbar__title">{{ ui.activeLabel }}</h2>
      <span class="topbar__crumb">电商销售数据洞察看板</span>
    </div>

    <div class="topbar__right">
      <!-- 搜索：直接作用于订单明细查询（keyword 参数）；AI 问答页不展示 -->
      <div v-if="ui.activeMenu !== 'assistant'" class="topbar__search">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14ZM20 20l-3.6-3.6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
        </svg>
        <input
          v-model="keyword"
          type="text"
          aria-label="搜索订单号或商品名"
          placeholder="搜索订单号 / 商品名"
          @keyup.enter="applySearch"
        />
      </div>

      <!-- 通知中心：由真实动作产生（导出/导入/刷新） -->
      <el-popover placement="bottom-end" :width="300" trigger="click" @show="ui.markAllRead()">
        <template #reference>
          <button type="button" class="topbar__icon-btn" aria-label="通知">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M18 15v-4a6 6 0 1 0-12 0v4l-1.5 3h15L18 15ZM9.5 21h5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <span v-if="ui.unreadCount" class="topbar__badge">{{ ui.unreadCount > 9 ? '9+' : ui.unreadCount }}</span>
          </button>
        </template>
        <div class="notice">
          <div class="notice__head">
            <b>通知</b>
            <button v-if="ui.notices.length" type="button" @click="ui.clearNotices()">清空</button>
          </div>
          <ul v-if="ui.notices.length" class="notice__list">
            <li v-for="item in ui.notices" :key="item.id" class="notice__item">
              <i :class="`is-${item.type}`"></i>
              <div>
                <p>{{ item.title }}</p>
                <span>{{ item.time }}</span>
              </div>
            </li>
          </ul>
          <p v-else class="notice__empty">暂无通知</p>
        </div>
      </el-popover>

      <!-- 用户 -->
      <el-dropdown trigger="click">
        <div class="topbar__user">
          <span class="topbar__avatar">数</span>
          <span class="topbar__user-meta">
            <b>数据分析师</b>
            <em>admin@eshop.com</em>
          </span>
        </div>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item>个人中心</el-dropdown-item>
            <el-dropdown-item>切换主题</el-dropdown-item>
            <el-dropdown-item divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup>
import { ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useUiStore } from '@/store/ui'
import { useDashboardStore } from '@/store/dashboard'

const ui = useUiStore()
const store = useDashboardStore()
const { keyword: storeKeyword } = storeToRefs(store)

const keyword = ref(store.keyword)

/**
 * 搜索框回车：写入 keyword（A2 查询 / A12 导出支持该参数）
 *
 * 这里刻意不走 setFilters：keyword 只是订单明细的检索条件，
 * A3/A4/A5 等统计接口文档中并不声明该参数，若 bump revision 会白白重发十几个
 * 与关键字无关的图表请求。改走 setOrderQuery 后，仅订单表格重新查询
 *（表格自身 watch orderParams 触发），并回到第一页。
 */
function applySearch() {
  const text = keyword.value.trim()
  store.setOrderQuery({ keyword: text, orderPage: 1 })
  ui.pushNotice(text ? `订单明细检索：${text}` : '已清空检索关键字', 'info')
}

// 外部（重置筛选等）修改后同步回输入框
watch(storeKeyword, (value) => {
  if (value !== keyword.value) keyword.value = value
})
</script>

<style lang="scss" scoped>
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: var(--h-topbar);
  padding: 0 24px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--bd-light);
  flex-shrink: 0;

  &__left {
    display: flex;
    align-items: baseline;
    gap: 10px;
    min-width: 0;
  }

  &__title {
    font-size: 15px;
    font-weight: 600;
    color: var(--t-main);
    white-space: nowrap;
  }

  /* 轻量面包屑：替代原日期选择器，仅作定位提示，不占交互 */
  &__crumb {
    font-size: 12px;
    color: var(--t-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;

    @media (max-width: 960px) {
      display: none;
    }
  }

  &__right {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__search {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 240px;
    height: 34px;
    padding: 0 12px;
    border: 1px solid var(--bd-base);
    border-radius: var(--r-control);
    background: var(--bg-soft);
    transition: all 0.16s ease;

    &:focus-within {
      background: var(--bg-card);
      border-color: var(--c-primary);
      box-shadow: 0 0 0 3px var(--c-primary-50);
    }

    svg {
      width: 15px;
      height: 15px;
      color: var(--t-muted);
      flex-shrink: 0;
    }

    input {
      flex: 1;
      min-width: 0;
      border: none;
      outline: none;
      background: transparent;
      font-size: 13px;
      font-family: inherit;
      color: var(--t-main);

      &::placeholder {
        color: var(--t-muted);
      }
    }
  }

  &__icon-btn {
    position: relative;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    border: 1px solid var(--bd-base);
    border-radius: var(--r-control);
    background: var(--bg-card);
    color: var(--t-sub);
    cursor: pointer;
    transition: all 0.16s ease;

    &:hover {
      color: var(--t-main);
      background: var(--bg-head);
    }

    svg {
      width: 17px;
      height: 17px;
    }
  }

  &__badge {
    position: absolute;
    top: -5px;
    right: -5px;
    min-width: 16px;
    height: 16px;
    padding: 0 4px;
    border-radius: 999px;
    background: var(--c-danger);
    color: #fff;
    font-size: 10px;
    line-height: 16px;
    text-align: center;
    font-variant-numeric: tabular-nums;
  }

  &__user {
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 3px 8px 3px 3px;
    border-radius: var(--r-pill);
    cursor: pointer;
    transition: background 0.16s ease;

    &:hover {
      background: var(--bg-head);
    }
  }

  &__avatar {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 50%;
    background: linear-gradient(135deg, var(--c-primary), var(--c-purple));
    color: #fff;
    font-size: 13px;
    font-weight: 600;
  }

  &__user-meta {
    display: flex;
    flex-direction: column;
    line-height: 1.2;

    b {
      font-size: 12.5px;
      font-weight: 500;
      color: var(--t-main);
    }

    em {
      font-style: normal;
      font-size: 10.5px;
      color: var(--t-muted);
    }
  }
}

.notice {
  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 10px;
    border-bottom: 1px solid var(--bd-light);

    b {
      font-size: 13px;
      color: var(--t-main);
    }

    button {
      border: none;
      background: transparent;
      color: var(--c-primary);
      font-size: 12px;
      font-family: inherit;
      cursor: pointer;
    }
  }

  &__list {
    list-style: none;
    margin: 0;
    padding: 4px 0 0;
    max-height: 280px;
    overflow-y: auto;
  }

  &__item {
    display: flex;
    gap: 10px;
    padding: 8px 0;

    i {
      width: 6px;
      height: 6px;
      margin-top: 6px;
      border-radius: 50%;
      flex-shrink: 0;
      background: var(--c-primary);

      &.is-success {
        background: var(--c-success);
      }

      &.is-warning {
        background: var(--c-warning);
      }

      &.is-danger {
        background: var(--c-danger);
      }
    }

    p {
      margin: 0;
      font-size: 12.5px;
      color: var(--t-main);
    }

    span {
      font-size: 11px;
      color: var(--t-muted);
    }
  }

  &__empty {
    margin: 0;
    padding: 24px 0;
    text-align: center;
    font-size: 12.5px;
    color: var(--t-muted);
  }
}
</style>
