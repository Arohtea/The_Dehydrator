<script setup>
/**
 * 管理员登录页。
 *
 * 登录成功后优先回到路由守卫记录的站内地址；对重定向参数做站内路径限制，避免
 * 把登录流程变成可利用的外部跳转入口。
 */
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '@/api'

const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

/**
 * 提交登录表单并处理登录后的安全重定向。
 *
 * 空表单和重复提交在客户端直接忽略，服务端返回的错误只展示可读消息，不在页面
 * 状态中保留或输出密码等敏感字段。
 *
 * @returns {Promise<void>} 登录请求和成功后的路由替换完成后返回。
 */
async function submit() {
  if (loading.value || !username.value.trim() || !password.value) return
  loading.value = true
  error.value = ''
  try {
    await login(username.value.trim(), password.value)
    const requestedRedirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    const redirect = requestedRedirect.startsWith('/') && !requestedRedirect.startsWith('//')
      ? requestedRedirect
      : '/'
    await router.replace(redirect)
  } catch (requestError) {
    error.value = requestError.response?.data?.error || '登录失败，请检查管理员凭据'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="mx-auto flex min-h-[70vh] max-w-md items-center px-4">
    <form class="w-full rounded-xl border border-border bg-white p-6 shadow-sm" @submit.prevent="submit">
      <h1 class="font-heading text-2xl font-bold text-text">管理员登录</h1>
      <p class="mt-2 text-sm text-text-muted">登录后管理文档、分析任务和资料库。</p>

      <label class="mt-6 block text-sm font-medium text-text">
        用户名
        <input v-model="username" autocomplete="username" class="mt-2 w-full rounded-lg border border-border px-3 py-2" />
      </label>

      <label class="mt-4 block text-sm font-medium text-text">
        密码
        <input v-model="password" type="password" autocomplete="current-password" class="mt-2 w-full rounded-lg border border-border px-3 py-2" />
      </label>

      <p v-if="error" class="mt-4 text-sm text-red-600">{{ error }}</p>
      <button type="submit" :disabled="loading" class="mt-6 w-full rounded-lg bg-primary px-4 py-2 text-sm text-white disabled:opacity-50">
        {{ loading ? '登录中...' : '登录' }}
      </button>
    </form>
  </main>
</template>
