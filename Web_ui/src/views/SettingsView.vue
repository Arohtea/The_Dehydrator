<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { getSettings, saveSettings } from '@/api'
import gsap from 'gsap'

const form = ref({ apiKey: '', model: '', mapWorkers: null, chunkSize: null, chunkOverlap: null })
const saving = ref(false)
const msg = ref('')

const containerRef = ref(null)

onMounted(async () => {
  try {
    const { data } = await getSettings()
    Object.assign(form.value, data)
  } catch {}

  await nextTick()
  if (containerRef.value) {
    const items = containerRef.value.querySelectorAll('.gs-setting-item')
    gsap.fromTo(items,
      { y: 20, opacity: 0 },
      { y: 0, opacity: 1, duration: 0.5, stagger: 0.1, ease: 'power2.out' }
    )
  }
})

async function onSave() {
  saving.value = true
  msg.value = ''
  try {
    await saveSettings(form.value)
    msg.value = '保存成功'
    const { data } = await getSettings()
    Object.assign(form.value, data)
  } catch {
    msg.value = '保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="max-w-lg mx-auto" ref="containerRef">
    <h1 class="font-heading text-2xl font-bold mb-6 gs-setting-item opacity-0">设置</h1>

    <div class="space-y-5">
      <div class="gs-setting-item opacity-0">
        <label class="block text-sm font-medium mb-1">API Key</label>
        <input v-model="form.apiKey" type="text" placeholder="输入智谱AI API Key"
          class="w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
        <p class="text-xs text-text-muted mt-1">智谱AI平台的API密钥，用于文档向量化和AI分析</p>
      </div>

      <div class="gs-setting-item opacity-0">
        <label class="block text-sm font-medium mb-1">模型</label>
        <input v-model="form.model" type="text" placeholder="如 glm-4.6"
          class="w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
        <p class="text-xs text-text-muted mt-1">分析使用的LLM模型，推荐使用glm-4.6</p>
      </div>

      <div class="gs-setting-item opacity-0">
        <label class="block text-sm font-medium mb-1">并发数</label>
        <input v-model.number="form.mapWorkers" type="number" min="1" max="8" placeholder="默认 2"
          class="w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
        <p class="text-xs text-text-muted mt-1">MAP阶段并行提取论据的线程数，越大越快但消耗更多API额度（默认2）</p>
      </div>

      <div class="gs-setting-item opacity-0">
        <label class="block text-sm font-medium mb-1">分块大小</label>
        <input v-model.number="form.chunkSize" type="number" min="500" max="8000" placeholder="默认 2000"
          class="w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
        <p class="text-xs text-text-muted mt-1">文档切分的每块字符数，越大上下文越完整但可能超出模型限制（默认2000）</p>
      </div>

      <div class="gs-setting-item opacity-0">
        <label class="block text-sm font-medium mb-1">分块重叠</label>
        <input v-model.number="form.chunkOverlap" type="number" min="0" max="2000" placeholder="默认 300"
          class="w-full border border-border rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary" />
        <p class="text-xs text-text-muted mt-1">相邻块之间的重叠字符数，避免论据被截断（默认300）</p>
      </div>

      <div class="flex items-center gap-3 pt-2 gs-setting-item opacity-0">
        <button @click="onSave" :disabled="saving"
          class="bg-primary text-white px-5 py-2 rounded-lg text-sm hover:bg-primary/90 disabled:opacity-50 transition-colors">
          {{ saving ? '保存中...' : '保存' }}
        </button>
        <span v-if="msg" class="text-sm" :class="msg === '保存成功' ? 'text-green-600' : 'text-red-500'">{{ msg }}</span>
      </div>
    </div>
  </div>
</template>
