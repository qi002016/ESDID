import { defineStore } from 'pinia'

/** 导航项定义（侧边栏与顶部标题共用） */
export const MENU_LIST = [
  { key: 'overview', label: '数据总览', icon: 'grid' },
  { key: 'orders', label: '订单管理', icon: 'list' },
  { key: 'users', label: '用户分析', icon: 'users' },
  { key: 'products', label: '商品分析', icon: 'box' },
  { key: 'marketing', label: '营销活动', icon: 'megaphone' },
  { key: 'assistant', label: 'AI 智能问答', icon: 'chat' }
]

/**
 * 界面状态：当前菜单 + 通知中心
 * 通知由真实动作产生（导出成功、导入完成、刷新数据等），铃铛角标反映未读数
 */
export const useUiStore = defineStore('ui', {
  state: () => ({
    activeMenu: 'overview',
    notices: [],
    noticeSeq: 0
  }),

  getters: {
    activeLabel(state) {
      return MENU_LIST.find((item) => item.key === state.activeMenu)?.label || '数据总览'
    },
    unreadCount(state) {
      return state.notices.filter((item) => !item.read).length
    }
  },

  actions: {
    setMenu(key) {
      this.activeMenu = key
    },
    /** 推送一条通知（type: success | info | warning | danger） */
    pushNotice(title, type = 'info') {
      this.notices.unshift({
        id: ++this.noticeSeq,
        title,
        type,
        time: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        read: false
      })
      // 只保留最近 20 条，避免无限增长
      if (this.notices.length > 20) this.notices.length = 20
    },
    markAllRead() {
      this.notices.forEach((item) => {
        item.read = true
      })
    },
    clearNotices() {
      this.notices = []
    }
  }
})
