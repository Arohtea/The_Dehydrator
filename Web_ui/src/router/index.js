import { createRouter, createWebHistory } from 'vue-router'
import { getCurrentUser } from '@/api'

/**
 * 前端路由定义。
 *
 * 路由本身只负责页面懒加载，登录校验统一放在下方全局守卫，避免每个页面重复
 * 请求当前用户并分别处理未登录状态。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      component: () => import('@/views/LoginView.vue'),
    },
    {
      path: '/',
      component: () => import('@/views/UploadView.vue'),
    },
    {
      path: '/documents',
      component: () => import('@/views/DocumentList.vue'),
    },
    {
      path: '/documents/:id',
      component: () => import('@/views/AnalysisResult.vue'),
      props: true,
    },
    {
      path: '/settings',
      component: () => import('@/views/SettingsView.vue'),
    },
    {
      path: '/reference-libraries',
      component: () => import('@/views/ReferenceLibraryView.vue'),
    },
  ],
})

// 登录页允许匿名访问，其余页面先确认当前会话，再决定是否放行。
router.beforeEach(async (to) => {
  if (to.path === '/login') return true
  try {
    await getCurrentUser()
    return true
  } catch (error) {
    // 只有明确的 401 才转登录；网络故障等异常暂不阻断页面，交由页面请求显示错误。
    if (error.response?.status === 401) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    return true
  }
})

export default router
