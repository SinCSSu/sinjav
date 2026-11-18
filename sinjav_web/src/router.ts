import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
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

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - sinJav` : 'sinJav'
})

export default router
