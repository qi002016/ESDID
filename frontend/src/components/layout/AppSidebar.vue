<template>
  <!-- 左侧深色侧边栏：Logo + 导航 + 底部数据导入 -->
  <aside class="sidebar">
    <div class="sidebar__brand">
      <span class="sidebar__logo">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M4 19V9M10 19V5M16 19v-7M22 19H2" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
        </svg>
      </span>
      <span class="sidebar__brand-text">
        <b>电商数据看板</b>
        <em>Sales Insight</em>
      </span>
    </div>

    <nav class="sidebar__nav">
      <p class="sidebar__group">分析</p>
      <button
        v-for="item in menus"
        :key="item.key"
        type="button"
        class="sidebar__item"
        :class="{ 'is-active': item.key === ui.activeMenu }"
        @click="ui.setMenu(item.key)"
      >
        <svg class="sidebar__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path :d="ICONS[item.icon]" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <div class="sidebar__footer">
      <button type="button" class="sidebar__import" @click="emit('import')">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 16V4m0 0L8 8m4-4 4 4M4 17v2a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-2" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        导入 CSV 数据
      </button>
      <p class="sidebar__version">v1.0 · 19 个接口</p>
    </div>
  </aside>
</template>

<script setup>
import { MENU_LIST, useUiStore } from '@/store/ui'

const emit = defineEmits(['import'])
const ui = useUiStore()
const menus = MENU_LIST

/** 内联 SVG 图标（不引入图标库） */
const ICONS = {
  grid: 'M4 13h6V4H4v9Zm0 7h6v-4H4v4Zm10 0h6v-9h-6v9Zm0-16v4h6V4h-6Z',
  list: 'M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01',
  users: 'M16 19v-1.5a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4V19M9.5 10.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7M21 19v-1.5a4 4 0 0 0-3-3.87M16.5 3.8a3.5 3.5 0 0 1 0 6.9',
  box: 'M20.5 7.5v9l-8.5 4.5L3.5 16.5v-9L12 3l8.5 4.5ZM3.5 7.5 12 12l8.5-4.5M12 12v9',
  chat: 'M21 11.5a8.5 8.5 0 0 1-8.5 8.5c-1.4 0-2.7-.33-3.86-.92L3 20l1.06-3.86A8.5 8.5 0 1 1 21 11.5ZM8.5 11.5h.01M12.25 11.5h.01M16 11.5h.01',
  megaphone: 'M3 11v2a1 1 0 0 0 1 1h2l5 4V6L6 10H4a1 1 0 0 0-1 1ZM15 9.5a2.5 2.5 0 0 1 0 5M18 6.5a6.5 6.5 0 0 1 0 11',
  gear: 'M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4ZM12 2.5v2.6M12 18.9v2.6M4.4 4.4l1.9 1.9M17.7 17.7l1.9 1.9M2.5 12h2.6M18.9 12h2.6M4.4 19.6l1.9-1.9M17.7 6.3l1.9-1.9'
}
</script>

<style lang="scss" scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  width: var(--w-sidebar);
  flex: 0 0 var(--w-sidebar);
  height: 100%;
  background: var(--bg-sidebar);
  color: var(--side-text);

  &__brand {
    display: flex;
    align-items: center;
    gap: 10px;
    height: var(--h-topbar);
    padding: 0 18px;
    flex-shrink: 0;
  }

  &__logo {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border-radius: 9px;
    /* 深墨底上用主色徽标块，保持 Logo 的强调感 */
    background: var(--side-accent);
    color: #fff;
    flex-shrink: 0;

    svg {
      width: 17px;
      height: 17px;
    }
  }

  &__brand-text {
    display: flex;
    flex-direction: column;
    line-height: 1.25;

    b {
      font-size: 13.5px;
      font-weight: 600;
      color: #fff;
      letter-spacing: 0.2px;
    }

    em {
      font-style: normal;
      font-size: 10.5px;
      color: #7c8273;
      letter-spacing: 0.4px;
    }
  }

  &__nav {
    flex: 1;
    padding: 10px 12px;
    overflow-y: auto;
  }

  &__group {
    margin: 6px 8px 8px;
    font-size: 10.5px;
    letter-spacing: 0.8px;
    text-transform: uppercase;
    color: #6e7568;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    height: 40px;
    padding: 0 12px;
    margin-bottom: 2px;
    border: none;
    border-radius: var(--r-control);
    background: transparent;
    color: var(--side-text);
    font-size: 13.5px;
    font-family: inherit;
    text-align: left;
    cursor: pointer;
    position: relative;
    transition: background 0.16s ease, color 0.16s ease;

    &:hover {
      background: var(--bg-sidebar-hover);
      color: var(--side-text-active);
    }

    &:focus-visible {
      outline: 2px solid var(--c-primary);
      outline-offset: -2px;
    }

    &.is-active {
      background: var(--bg-sidebar-active);
      color: var(--side-text-active);
      font-weight: 500;

      /* 左侧主色高亮条 */
      &::before {
        content: '';
        position: absolute;
        left: -12px;
        top: 9px;
        bottom: 9px;
        width: 3px;
        border-radius: 0 3px 3px 0;
        background: var(--side-accent);
      }
    }
  }

  &__icon {
    width: 18px;
    height: 18px;
    flex-shrink: 0;
  }

  &__footer {
    padding: 12px;
    border-top: 1px solid rgba(255, 255, 255, 0.06);
    flex-shrink: 0;
  }

  &__import {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    width: 100%;
    height: 36px;
    border: 1px solid rgba(255, 255, 255, 0.14);
    border-radius: var(--r-control);
    background: rgba(255, 255, 255, 0.05);
    color: #c6ccbf;
    font-size: 12.5px;
    font-family: inherit;
    cursor: pointer;
    transition: all 0.16s ease;

    svg {
      width: 15px;
      height: 15px;
    }

    &:hover {
      background: var(--c-primary);
      border-color: var(--c-primary);
      color: #fff;
    }

    &:focus-visible {
      outline: 2px solid var(--c-primary);
      outline-offset: -2px;
    }
  }

  &__version {
    margin: 10px 0 0;
    text-align: center;
    font-size: 10.5px;
    color: #6e7568;
  }
}
</style>
