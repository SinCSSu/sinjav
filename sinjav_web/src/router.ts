import { createRouter, createWebHashHistory } from 'vue-router'
import { isLoggedIn } from './auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    {
      path: '/login',
      name: 'login',
      component: () => import('./views/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('./views/DashboardView.vue'),
      meta: { title: '仪表盘' },
    },
    {
      path: '/movies',
      name: 'movies',
      component: () => import('./views/MoviesView.vue'),
      meta: { title: '影片库' },
    },
    {
      path: '/file-records',
      name: 'file-records',
      component: () => import('./views/FileRecordsView.vue'),
      meta: { title: '文件记录' },
    },
    {
      path: '/jobs',
      name: 'jobs',
      component: () => import('./views/JobsView.vue'),
      meta: { title: '任务管理' },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('./views/SettingsView.vue'),
      meta: { title: '设置' },
    },
  ],
})

// 登录守卫：未登录访问受保护页面 → 跳登录页并记录原目标
router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!isLoggedIn()) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - sinJav` : 'sinJav'
})

export default router
