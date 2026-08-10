<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { getSettings, saveSettings } from '@/api'
import gsap from 'gsap'

const emptyModel = () => ({
  model: '',
  url: '',
  apiKey: '',
  apiKeyConfigured: false,
  apiKeyPreview: null,
})

const form = ref({
  textModel: emptyModel(),
  vectorModel: emptyModel(),
  tavilyApiKey: '',
  tavilyApiKeyConfigured: false,
  tavilyApiKeyPreview: null,
  mapWorkers: null,
  chunkSize: null,
  chunkOverlap: null,
})
const saving = ref(false)
const msg = ref('')
const containerRef = ref(null)

function applySettings(data) {
  const textModel = data?.textModel || {}
  const vectorModel = data?.vectorModel || {}
  form.value.textModel = {
    ...emptyModel(),
    ...textModel,
    apiKey: '',
  }
  form.value.vectorModel = {
    ...emptyModel(),
    ...vectorModel,
    apiKey: '',
  }
  form.value.tavilyApiKey = ''
  form.value.tavilyApiKeyConfigured = Boolean(data?.tavilyApiKeyConfigured)
  form.value.tavilyApiKeyPreview = data?.tavilyApiKeyPreview || null
  form.value.mapWorkers = data?.mapWorkers ?? null
  form.value.chunkSize = data?.chunkSize ?? null
  form.value.chunkOverlap = data?.chunkOverlap ?? null
}

function settingsPayload() {
  return {
    textModel: {
      model: form.value.textModel.model,
      url: form.value.textModel.url,
      apiKey: form.value.textModel.apiKey,
    },
    vectorModel: {
      model: form.value.vectorModel.model,
      url: form.value.vectorModel.url,
      apiKey: form.value.vectorModel.apiKey,
    },
    tavilyApiKey: form.value.tavilyApiKey,
    mapWorkers: form.value.mapWorkers,
    chunkSize: form.value.chunkSize,
    chunkOverlap: form.value.chunkOverlap,
  }
}

onMounted(async () => {
  try {
    const { data } = await getSettings()
    applySettings(data)
  } catch {}

  await nextTick()
  if (containerRef.value) {
    const items = containerRef.value.querySelectorAll('.gs-setting-item')
    gsap.fromTo(items,
      { y: 20, opacity: 0 },
      { y: 0, opacity: 1, duration: 0.5, stagger: 0.08, ease: 'power2.out' },
    )
  }
})

async function onSave() {
  saving.value = true
  msg.value = ''
  try {
    await saveSettings(settingsPayload())
    msg.value = '保存成功'
    const { data } = await getSettings()
    applySettings(data)
  } catch (error) {
    msg.value = error?.response?.data?.error || '保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div ref="containerRef" class="max-w-2xl mx-auto">
    <h1 class="font-heading text-2xl font-bold mb-2 gs-setting-item opacity-0">AI 设置</h1>
    <p class="text-sm text-text-muted mb-8 gs-setting-item opacity-0">
      文本生成和向量检索使用独立的 OpenAI 兼容模型配置。
    </p>

    <div class="space-y-8">
      <section class="gs-setting-item opacity-0">
        <div class="border-b border-border pb-3 mb-5">
          <h2 class="font-heading text-xl font-semibold">文本模型</h2>
          <p class="text-xs text-text-muted mt-1">用于论据提取、逻辑检测、交叉验证和资料分类。</p>
        </div>
        <div class="grid gap-4 sm:grid-cols-2">
          <label class="block text-sm font-medium">
            模型名称
            <input v-model="form.textModel.model" type="text" maxlength="100" placeholder="模型名称"
              class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
          </label>
          <label class="block text-sm font-medium">
            接口 URL
            <input v-model="form.textModel.url" type="url" maxlength="2048" placeholder="https://.../v1/"
              class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
          </label>
        </div>
        <label class="block text-sm font-medium mt-4">
          API Key
          <input v-model="form.textModel.apiKey" type="password" autocomplete="new-password" maxlength="512"
            placeholder="已有配置时留空表示保持原值"
            class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
        </label>
        <p class="text-xs text-text-muted mt-2">
          {{ form.textModel.apiKeyConfigured ? `已配置（${form.textModel.apiKeyPreview}）；留空表示保持原值` : '尚未配置 API Key' }}
        </p>
      </section>

      <section class="gs-setting-item opacity-0">
        <div class="border-b border-border pb-3 mb-5">
          <h2 class="font-heading text-xl font-semibold">向量模型</h2>
          <p class="text-xs text-text-muted mt-1">用于文档向量化和参考资料检索；切换不同维度的模型前需先重建向量。</p>
        </div>
        <div class="grid gap-4 sm:grid-cols-2">
          <label class="block text-sm font-medium">
            模型名称
            <input v-model="form.vectorModel.model" type="text" maxlength="100" placeholder="模型名称"
              class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
          </label>
          <label class="block text-sm font-medium">
            接口 URL
            <input v-model="form.vectorModel.url" type="url" maxlength="2048" placeholder="https://.../v1/"
              class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
          </label>
        </div>
        <label class="block text-sm font-medium mt-4">
          API Key
          <input v-model="form.vectorModel.apiKey" type="password" autocomplete="new-password" maxlength="512"
            placeholder="已有配置时留空表示保持原值"
            class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
        </label>
        <p class="text-xs text-text-muted mt-2">
          {{ form.vectorModel.apiKeyConfigured ? `已配置（${form.vectorModel.apiKeyPreview}）；留空表示保持原值` : '尚未配置 API Key' }}
        </p>
      </section>

      <section class="gs-setting-item opacity-0">
        <div class="border-b border-border pb-3 mb-5">
          <h2 class="font-heading text-xl font-semibold">联网验证</h2>
          <p class="text-xs text-text-muted mt-1">深度分析需要 Tavily API Key，快速分析不使用联网搜索。</p>
        </div>
        <label class="block text-sm font-medium">
          Tavily API Key
          <input v-model="form.tavilyApiKey" type="password" autocomplete="new-password" maxlength="512"
            placeholder="留空表示保持原值"
            class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
        </label>
        <p class="text-xs text-text-muted mt-2">
          {{ form.tavilyApiKeyConfigured ? `已配置（${form.tavilyApiKeyPreview}）；留空表示保持原值` : '尚未配置 Tavily API Key，深度分析将不可用' }}
        </p>
      </section>

      <section class="gs-setting-item opacity-0">
        <div class="border-b border-border pb-3 mb-5">
          <h2 class="font-heading text-xl font-semibold">处理参数</h2>
        </div>
        <div class="grid gap-4 sm:grid-cols-3">
          <label class="block text-sm font-medium">
            分析并发数
            <input v-model.number="form.mapWorkers" type="number" min="1" max="8"
              class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
          </label>
          <label class="block text-sm font-medium">
            分块大小
            <input v-model.number="form.chunkSize" type="number" min="500" max="8000"
              class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
          </label>
          <label class="block text-sm font-medium">
            分块重叠
            <input v-model.number="form.chunkOverlap" type="number" min="0" max="8000"
              class="mt-1 w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
          </label>
        </div>
      </section>

      <div class="flex items-center gap-3 pt-1 gs-setting-item opacity-0">
        <button @click="onSave" :disabled="saving"
          class="bg-primary text-white px-5 py-2 rounded-lg text-sm hover:bg-primary/90 disabled:opacity-50 transition-colors">
          {{ saving ? '保存中...' : '保存设置' }}
        </button>
        <span v-if="msg" class="text-sm" :class="msg === '保存成功' ? 'text-green-600' : 'text-red-500'">{{ msg }}</span>
      </div>
    </div>
  </div>
</template>
