<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch, computed } from 'vue'
import { useDocumentStore } from '@/stores/document'
import { storeToRefs } from 'pinia'
import { Loader2, X, Info } from 'lucide-vue-next'
import * as api from '@/api'
import ArgumentChain from '@/components/ArgumentChain.vue'
import LogicFlaws from '@/components/LogicFlaws.vue'
import CrossValidation from '@/components/CrossValidation.vue'
import gsap from 'gsap'

const props = defineProps({ id: String })
const store = useDocumentStore()
const currentTask = ref(null)
const polling = ref(false)
const streamText = ref('')
const streamStep = ref('')
let timer = null
let eventSource = null

const containerRef = ref(null)
const tabs = ['论据链', '逻辑漏洞', '交叉验证']
const activeTab = ref(0)

// 侦听 tab 切换，给切换的内容加个淡入
watch(activeTab, async () => {
  await nextTick()
  if (containerRef.value) {
    const tabContent = containerRef.value.querySelector('.tab-content-enter')
    if (tabContent) {
      gsap.fromTo(tabContent, 
        { y: 20, opacity: 0 }, 
        { y: 0, opacity: 1, duration: 0.6, ease: 'power2.out' }
      )
    }
  }
})

// 初始挂载时，如果已有 task 的话，做个总控的入场
// （配合 App.vue 的整体进场，这里做小范围递进）
watch(currentTask, async (oldVal, newVal) => {
  if (!oldVal && newVal) {
    await nextTick()
    gsap.fromTo('.gs-task-reveal', 
      { opacity: 0, y: 15 }, 
      { opacity: 1, y: 0, stagger: 0.1, duration: 0.8, ease: 'power3.out' }
    )
  }
}, { immediate: true })

onMounted(async () => {
  const { data: tasks } = await api.getTasksByDocument(props.id)
  if (tasks.length) {
    currentTask.value = tasks[tasks.length - 1]
    store.currentTask = currentTask.value
    if (!['COMPLETED', 'FAILED', 'CANCELLED'].includes(currentTask.value.status)) startPolling(currentTask.value.id)
  }
})

function startPolling(taskId) {
  polling.value = true
  connectSSE(taskId)
  timer = setInterval(async () => {
    const task = await store.pollTask(taskId)
    currentTask.value = task
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status)) {
      clearInterval(timer)
      polling.value = false
      closeSSE()
    }
  }, 3000)
}

function connectSSE(taskId) {
  eventSource = new EventSource(`/api/analysis/stream/${taskId}`)
  let lastStep = ''
  eventSource.onmessage = (e) => {
    const msg = JSON.parse(e.data)
    if (msg.step !== lastStep) {
      lastStep = msg.step
      streamStep.value = msg.step
      streamText.value = ''
    }
    if (msg.token) streamText.value += msg.token
  }
}

function closeSSE() {
  if (eventSource) { eventSource.close(); eventSource = null }
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
  closeSSE()
})

const cancelling = ref(false)

async function handleCancel() {
  if (!currentTask.value) return
  cancelling.value = true
  try {
    const { data } = await api.cancelTask(currentTask.value.id)
    currentTask.value = data
    store.currentTask = data
    clearInterval(timer)
    polling.value = false
    closeSSE()
  } finally {
    cancelling.value = false
  }
}

function parseJson(str) {
  if (!str) return null
  try { return JSON.parse(str) } catch { return str }
}
</script>

<template>
  <div ref="containerRef">
    <div v-if="!currentTask" class="text-center py-20 text-text-muted">
      该文档暂无分析任务
    </div>

    <template v-else>
      <!-- 状态栏 -->
      <div v-if="polling" class="mb-6 gs-task-reveal">
        <div class="flex items-center justify-between mb-2">
          <div class="flex items-center gap-2">
            <Loader2 class="w-4 h-4 text-accent animate-spin" />
            <span class="text-sm text-text-muted">
              {{ currentTask.currentStep || '分析中...' }}
            </span>
          </div>
          <span class="text-sm font-medium text-accent">
            {{ currentTask.progress || 0 }}%
          </span>
          <button @click="handleCancel" :disabled="cancelling"
            class="text-xs px-2 py-1 rounded border border-red-300 text-red-500 hover:bg-red-50 transition-colors cursor-pointer disabled:opacity-50">
            {{ cancelling ? '取消中...' : '中止分析' }}
          </button>
        </div>
        <div class="w-full h-2 bg-gray-200 rounded-full overflow-hidden">
          <div
            class="h-full bg-accent rounded-full transition-all duration-500"
            :style="{ width: (currentTask.progress || 0) + '%' }"
          />
        </div>
        <div class="mt-3 flex items-start gap-1.5 text-xs text-text-muted bg-blue-50/50 p-2 rounded-lg border border-blue-100">
          <Info class="w-4 h-4 text-accent shrink-0" />
          <p>深度分析涉及长文本的多维度提纯与验证，预计耗时 <strong class="text-accent/80 font-medium">5 ~ 20 分钟</strong> 不等。您可离开此页面，后台将持续分析。</p>
        </div>
      </div>
      <div v-else-if="currentTask.status === 'COMPLETED'" class="flex items-center gap-2 mb-6 gs-task-reveal">
        <span class="text-sm font-medium text-green-600">分析完成</span>
      </div>
      <div v-else-if="currentTask.status === 'CANCELLED'" class="flex items-center gap-2 mb-6 gs-task-reveal">
        <X class="w-4 h-4 text-red-500" />
        <span class="text-sm font-medium text-red-500">分析已取消</span>
      </div>

      <!-- Tab 栏 -->
      <div class="flex gap-1 border-b border-border mb-6 gs-task-reveal">
        <button
          v-for="(tab, i) in tabs" :key="tab"
          class="px-4 py-2 text-sm transition-colors duration-200 cursor-pointer"
          :class="activeTab === i
            ? 'text-primary border-b-2 border-primary font-medium'
            : 'text-text-muted hover:text-primary'"
          @click="activeTab = i"
        >
          {{ tab }}
        </button>
      </div>

      <!-- Tab 内容 -->
      <div v-if="currentTask.status !== 'COMPLETED'" class="tab-content-enter">
        <div v-if="streamText" data-lenis-prevent class="bg-gray-50 border border-border rounded-lg p-4 font-mono text-sm leading-relaxed whitespace-pre-wrap max-h-96 overflow-y-auto">
          <div class="text-xs text-text-muted mb-2">{{ streamStep }}</div>
          <div>{{ streamText }}<span class="animate-pulse">▌</span></div>
        </div>
        <div v-else class="space-y-3">
          <div v-for="i in 4" :key="i" class="h-6 bg-white border border-border rounded animate-pulse"></div>
        </div>
      </div>

      <div v-else class="tab-content-enter">
        <!-- 论据链 -->
        <ArgumentChain v-if="activeTab === 0" :data="parseJson(currentTask.argumentChain)" />
        <!-- 逻辑漏洞 -->
        <LogicFlaws v-if="activeTab === 1" :data="parseJson(currentTask.logicFlaws)" />
        <!-- 交叉验证 -->
        <CrossValidation v-if="activeTab === 2" :data="parseJson(currentTask.crossValidation)" />
      </div>
    </template>
  </div>
</template>
