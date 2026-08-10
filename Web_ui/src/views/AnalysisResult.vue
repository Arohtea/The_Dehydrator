<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useDocumentStore } from '@/stores/document'
import { Loader2, RefreshCw, X, Info } from 'lucide-vue-next'
import * as api from '@/api'
import ArgumentChain from '@/components/ArgumentChain.vue'
import LogicFlaws from '@/components/LogicFlaws.vue'
import CrossValidation from '@/components/CrossValidation.vue'
import gsap from 'gsap'

const props = defineProps({ id: String })
const store = useDocumentStore()
const currentTask = ref(null)
const loadingTask = ref(true)
const loadError = ref('')
const pollError = ref('')
const polling = ref(false)
const cancelling = ref(false)
const streamStages = ref([])
const streamStep = ref('')
const streamPanelRef = ref(null)
let timer = null
let reconnectTimer = null
let eventSource = null
let streamTaskId = null
let lastEventId = ''
let reconnectDelay = 1000
let streamTerminal = false
let loadSequence = 0

const containerRef = ref(null)
const tabs = ['论据链', '逻辑漏洞', '交叉验证']
const activeTab = ref(0)

const modeTextMap = {
  quick: '快速分析',
  deep: '深度分析',
}
const terminalStatuses = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const activeStatuses = new Set(['PENDING', 'PROCESSING', 'CANCELLING'])

const isActiveTask = computed(() => activeStatuses.has(currentTask.value?.status))
const currentModeLabel = computed(() => modeTextMap[currentTask.value?.mode] || '深度分析')
const referenceLibraryNames = computed(() => {
  const raw = currentTask.value?.referenceLibraryNames
  if (Array.isArray(raw)) return raw
  if (typeof raw !== 'string' || !raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
})

watch(activeTab, async () => {
  await nextTick()
  if (containerRef.value) {
    const tabContent = containerRef.value.querySelector('.tab-content-enter')
    if (tabContent) {
      gsap.fromTo(tabContent,
        { y: 20, opacity: 0 },
        { y: 0, opacity: 1, duration: 0.6, ease: 'power2.out' },
      )
    }
  }
})

watch(currentTask, async (newVal, oldVal) => {
  if (!oldVal && newVal) {
    await nextTick()
    gsap.fromTo('.gs-task-reveal',
      { opacity: 0, y: 15 },
      { opacity: 1, y: 0, stagger: 0.1, duration: 0.8, ease: 'power3.out' },
    )
  }
}, { immediate: true })

onMounted(loadLatestTask)

watch(() => props.id, (newId, oldId) => {
  if (!newId || newId === oldId) return
  stopPolling()
  closeSSE()
  if (reconnectTimer) clearTimeout(reconnectTimer)
  reconnectTimer = null
  currentTask.value = null
  store.currentTask = null
  resetStreamState()
  loadLatestTask()
})

onUnmounted(() => {
  stopPolling()
  closeSSE()
  if (reconnectTimer) clearTimeout(reconnectTimer)
})

async function loadLatestTask() {
  const requestSequence = ++loadSequence
  loadingTask.value = true
  loadError.value = ''
  try {
    const { data: tasks } = await api.getTasksByDocument(props.id)
    if (requestSequence !== loadSequence) return
    currentTask.value = tasks.length ? tasks[tasks.length - 1] : null
    store.currentTask = currentTask.value
    if (currentTask.value && isActiveTask.value) {
      startMonitoring(currentTask.value.id, true)
    } else if (currentTask.value) {
      resetStreamState()
      streamTaskId = currentTask.value.id
      connectSSE(currentTask.value.id)
    }
  } catch (error) {
    if (requestSequence !== loadSequence) return
    loadError.value = error?.response?.data?.error || '分析任务加载失败'
  } finally {
    if (requestSequence === loadSequence) loadingTask.value = false
  }
}

function startMonitoring(taskId, resetStream = false) {
  stopPolling()
  if (resetStream) resetStreamState()
  streamTaskId = taskId
  polling.value = true
  cancelling.value = currentTask.value?.status === 'CANCELLING'
  connectSSE(taskId)
  timer = setInterval(pollCurrentTask, 3000)
}

async function pollCurrentTask() {
  if (!streamTaskId) return
  try {
    pollError.value = ''
    const task = await store.pollTask(streamTaskId)
    currentTask.value = task
    if (terminalStatuses.has(task.status)) {
      stopPolling()
      closeSSE()
      cancelling.value = false
    }
  } catch {
    pollError.value = '状态同步暂时失败，系统会继续重试'
  }
}

function stopPolling() {
  if (timer) clearInterval(timer)
  timer = null
  polling.value = false
}

function connectSSE(taskId) {
  closeSSE()
  const query = lastEventId ? `?lastEventId=${encodeURIComponent(lastEventId)}` : ''
  eventSource = new EventSource(`/api/analysis/stream/${taskId}${query}`)
  eventSource.onopen = () => {
    reconnectDelay = 1000
    pollError.value = ''
  }
  eventSource.onmessage = (event) => {
    if (event.lastEventId) lastEventId = event.lastEventId
    let message
    try {
      message = JSON.parse(event.data)
    } catch {
      return
    }
    handleStreamMessage(message)
  }
  eventSource.onerror = () => {
    if (streamTerminal || !isActiveTask.value) return
    closeSSE()
    scheduleSSEReconnect()
  }
}

function scheduleSSEReconnect() {
  if (reconnectTimer || !streamTaskId || !isActiveTask.value) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connectSSE(streamTaskId)
    reconnectDelay = Math.min(reconnectDelay * 2, 15000)
  }, reconnectDelay)
}

function handleStreamMessage(message) {
  if (message.kind === 'progress') {
    if (currentTask.value) {
      currentTask.value.progress = Math.max(0, Math.min(100, Number(message.progress) || 0))
      currentTask.value.currentStep = message.currentStep || currentTask.value.currentStep
    }
    return
  }

  const step = message.step || '分析过程'
  let stage = streamStages.value.find(item => item.step === step)
  if (!stage) {
    stage = { step, text: '', done: false }
    streamStages.value.push(stage)
  }
  streamStep.value = step
  if (typeof message.token === 'string' && message.token) stage.text += message.token
  if (message.done) stage.done = true
  nextTick(() => {
    if (streamPanelRef.value) streamPanelRef.value.scrollTop = streamPanelRef.value.scrollHeight
  })

  if (['completed', 'failed', 'cancelled'].includes(String(message.kind).toLowerCase())) {
    streamTerminal = true
    closeSSE()
  }
}

function closeSSE() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

function resetStreamState() {
  streamStages.value = []
  streamStep.value = ''
  lastEventId = ''
  streamTerminal = false
}

async function handleCancel() {
  if (!currentTask.value || cancelling.value || !isActiveTask.value) return
  cancelling.value = true
  try {
    const { data } = await api.cancelTask(currentTask.value.id)
    currentTask.value = data
    store.currentTask = data
    if (terminalStatuses.has(data.status)) {
      stopPolling()
      closeSSE()
      cancelling.value = false
    }
  } catch (error) {
    cancelling.value = false
    alert(error?.response?.data?.error || '请求终止分析失败')
  }
}

function parseJson(value) {
  if (!value) return null
  if (typeof value === 'object') {
    return Object.prototype.hasOwnProperty.call(value, 'raw') ? null : value
  }
  try {
    const parsed = JSON.parse(value)
    if (!parsed || typeof parsed !== 'object') return null
    return Object.prototype.hasOwnProperty.call(parsed, 'raw') ? null : parsed
  } catch {
    return null
  }
}

function formatStep(step) {
  const normalized = String(step)
  if (normalized.startsWith('argument_chain_map_')) return `论据链分段 ${normalized.slice('argument_chain_map_'.length)}`
  if (normalized === 'argument_chain_reduce') return '论据链归并'
  if (normalized === 'logic_flaws') return '逻辑漏洞检测'
  if (normalized.startsWith('cross_validation_')) return `交叉验证 ${normalized.slice('cross_validation_'.length)}`
  return normalized
}
</script>

<template>
  <div ref="containerRef">
    <div v-if="loadingTask" class="space-y-3 py-12">
      <div v-for="i in 4" :key="i" class="h-6 rounded border border-border bg-white animate-pulse" />
    </div>

    <div v-else-if="loadError" class="py-20 text-center text-text-muted">
      <p>{{ loadError }}</p>
      <button class="mt-4 inline-flex items-center gap-2 rounded-lg border border-border px-4 py-2 text-sm hover:bg-gray-50" @click="loadLatestTask">
        <RefreshCw class="h-4 w-4" />
        重试
      </button>
    </div>

    <div v-else-if="!currentTask" class="text-center py-20 text-text-muted">
      该文档暂无分析任务
    </div>

    <template v-else>
      <div v-if="isActiveTask" class="mb-6 gs-task-reveal">
        <div class="flex items-center justify-between mb-2 gap-3">
          <div class="flex items-center gap-2 min-w-0">
            <Loader2 v-if="currentTask.status !== 'CANCELLING'" class="w-4 h-4 text-accent animate-spin shrink-0" />
            <Loader2 v-else class="w-4 h-4 text-amber-600 animate-spin shrink-0" />
            <span class="text-sm text-text-muted truncate">
              {{ currentTask.status === 'CANCELLING' ? '终止中' : currentModeLabel }} · {{ currentTask.currentStep || '分析中...' }}
            </span>
          </div>
          <span class="text-sm font-medium text-accent shrink-0">{{ currentTask.progress || 0 }}%</span>
          <button v-if="currentTask.status !== 'CANCELLING'" @click="handleCancel" :disabled="cancelling"
            class="text-xs px-2 py-1 rounded border border-red-300 text-red-500 hover:bg-red-50 transition-colors cursor-pointer disabled:opacity-50">
            {{ cancelling ? '终止中...' : '中止分析' }}
          </button>
          <span v-else class="text-xs text-amber-700 shrink-0">等待服务确认</span>
        </div>
        <div class="w-full h-2 bg-gray-200 rounded-full overflow-hidden">
          <div class="h-full bg-accent rounded-full transition-all duration-500" :style="{ width: (currentTask.progress || 0) + '%' }" />
        </div>
        <p v-if="pollError" class="mt-2 text-xs text-amber-700">{{ pollError }}</p>
        <div class="mt-3 flex items-start gap-1.5 text-xs text-text-muted bg-blue-50/50 p-2 rounded-lg border border-blue-100">
          <Info class="w-4 h-4 text-accent shrink-0" />
          <p v-if="currentTask.mode === 'quick'">快速分析会跳过联网验证，交叉验证基于模型自身知识和可选参考资料进行判断，不会用当前论文验证当前论文。</p>
          <p v-else>深度分析会结合模型自身知识、可选参考资料与联网搜索进行交叉验证，预计耗时 <strong class="text-accent/80 font-medium">5 ~ 20 分钟</strong> 不等。您可离开此页面，后台将持续分析。</p>
        </div>
      </div>
      <div v-else-if="currentTask.status === 'COMPLETED'" class="flex items-center gap-2 mb-6 gs-task-reveal">
        <span class="text-sm font-medium text-green-600">{{ currentModeLabel }}完成</span>
      </div>
      <div v-else-if="currentTask.status === 'CANCELLED'" class="flex items-center gap-2 mb-6 gs-task-reveal">
        <X class="w-4 h-4 text-red-500" />
        <span class="text-sm font-medium text-red-500">{{ currentModeLabel }}已取消</span>
      </div>
      <div v-else-if="currentTask.status === 'FAILED'" class="flex items-center gap-2 mb-6 gs-task-reveal">
        <X class="w-4 h-4 text-red-500" />
        <span class="text-sm font-medium text-red-500">{{ currentModeLabel }}失败：{{ currentTask.currentStep || '结果不可用' }}</span>
      </div>

      <div class="mb-6 rounded-lg border border-border bg-white px-4 py-3 text-sm text-text-muted gs-task-reveal">
        <span class="font-medium text-text">参考资料：</span>
        <span v-if="referenceLibraryNames.length">{{ referenceLibraryNames.join('、') }}</span>
        <span v-else>未使用参考资料</span>
      </div>

      <div class="flex gap-1 border-b border-border mb-6 gs-task-reveal">
        <button
          v-for="(tab, i) in tabs" :key="tab"
          class="px-4 py-2 text-sm transition-colors duration-200 cursor-pointer"
          :class="activeTab === i ? 'text-primary border-b-2 border-primary font-medium' : 'text-text-muted hover:text-primary'"
          @click="activeTab = i"
        >
          {{ tab }}
        </button>
      </div>

      <div v-if="streamStages.length" ref="streamPanelRef" data-lenis-prevent class="mb-6 space-y-3 max-h-[32rem] overflow-y-auto">
        <div class="mb-2 text-sm font-medium text-text">分析过程</div>
          <div v-for="stage in streamStages" :key="stage.step" class="rounded-lg border border-border bg-gray-50 p-4">
            <div class="mb-2 flex items-center justify-between gap-3 text-xs">
              <span class="font-medium text-text">{{ formatStep(stage.step) }}</span>
              <span :class="stage.done ? 'text-green-600' : 'text-accent'">{{ stage.done ? '已完成' : (stage.step === streamStep ? '进行中' : '已产生') }}</span>
            </div>
            <div v-if="stage.text" class="whitespace-pre-wrap text-sm leading-relaxed">{{ stage.text }}</div>
          </div>
      </div>

      <div v-else-if="isActiveTask" class="mb-6 space-y-3 tab-content-enter">
        <div v-for="i in 4" :key="i" class="h-6 bg-white border border-border rounded animate-pulse" />
      </div>

      <div v-if="currentTask.status === 'COMPLETED'" class="tab-content-enter">
        <ArgumentChain v-if="activeTab === 0" :data="parseJson(currentTask.argumentChain)" />
        <LogicFlaws v-if="activeTab === 1" :data="parseJson(currentTask.logicFlaws)" />
        <CrossValidation v-if="activeTab === 2" :data="parseJson(currentTask.crossValidation)" :mode="currentTask.mode" />
      </div>
    </template>
  </div>
</template>
