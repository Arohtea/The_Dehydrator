import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
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
  ],
})

export default router
