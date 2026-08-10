import { createRouter, createWebHistory } from 'vue-router'
import { getCurrentUser } from '@/api'

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

router.beforeEach(async (to) => {
  if (to.path === '/login') return true
  try {
    await getCurrentUser()
    return true
  } catch (error) {
    if (error.response?.status === 401) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    return true
  }
})

export default router
