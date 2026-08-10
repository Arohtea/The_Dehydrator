<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useDocumentStore } from '@/stores/document'
import { ChevronDown, Info, Loader2, RefreshCw, X } from 'lucide-vue-next'
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
const thinkingEntries = ref([])
const thinkingStep = ref('')
const analysisProcessExpanded = ref(true)
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

watch(() => currentTask.value?.status, status => {
  if (status !== 'COMPLETED') activeTab.value = 0
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
  resetStreamState(false)
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
      resetStreamState(false)
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
  if (resetStream) resetStreamState(true)
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
      finishThinkingEntries(task.status !== 'COMPLETED')
      analysisProcessExpanded.value = false
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
    recordProcessStage(message)
    return
  }

  if (message.kind === 'thinking') {
    recordThinking(message)
    return
  }

  const messageKind = String(message.kind || '').toLowerCase()
  if (['completed', 'failed', 'cancelled'].includes(messageKind)) {
    streamTerminal = true
    markProcessStagesDone()
    finishThinkingEntries(messageKind !== 'completed')
    analysisProcessExpanded.value = false
    closeSSE()
  }
}

function closeSSE() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

function resetStreamState(expanded) {
  streamStages.value = []
  streamStep.value = ''
  thinkingEntries.value = []
  thinkingStep.value = ''
  lastEventId = ''
  streamTerminal = false
  analysisProcessExpanded.value = expanded
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
      finishThinkingEntries(data.status !== 'COMPLETED')
      analysisProcessExpanded.value = false
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

function processStageKey(summary, progress) {
  if (progress >= 100 || summary.includes('分析完成')) return 'completed'
  if (summary.includes('检索文档片段')) return 'document_retrieval'
  if (summary.includes('提取论据')) return 'argument_extraction'
  if (summary.includes('整理完整论据链')) return 'argument_reduce'
  if (summary.includes('检测逻辑漏洞')) return 'logic_flaw_detection'
  if (summary.includes('交叉验证')) return 'cross_validation'
  return `other:${summary}`
}

function recordProcessStage(message) {
  const progress = Math.max(0, Math.min(100, Number(message.progress) || 0))
  const summary = String(message.currentStep || '正在分析')
  const key = processStageKey(summary, progress)
  let stage = streamStages.value.find(item => item.key === key)

  if (!stage) {
    streamStages.value.forEach(item => { item.done = true })
    stage = { key, summary, progress, done: key === 'completed' }
    streamStages.value.push(stage)
  } else {
    stage.summary = summary
    stage.progress = progress
    if (key === 'completed') stage.done = true
  }
  streamStep.value = key
}

function markProcessStagesDone() {
  streamStages.value.forEach(stage => { stage.done = true })
}

function recordThinking(message) {
  const step = String(message.step || 'analysis')
  let entry = thinkingEntries.value.find(item => item.step === step)
  if (!entry) {
    entry = { step, text: '', done: false, interrupted: false }
    thinkingEntries.value.push(entry)
  }
  if (message.reset) {
    entry.text = ''
    entry.done = false
    entry.interrupted = false
  }
  if (typeof message.text === 'string' && message.text) entry.text += message.text
  if (message.done) {
    entry.done = true
    entry.interrupted = false
  }
  thinkingStep.value = step
  nextTick(() => {
    if (containerRef.value && analysisProcessExpanded.value) {
      const panel = containerRef.value.querySelector('#analysis-process-panel')
      if (panel) panel.scrollTop = panel.scrollHeight
    }
  })
}

function finishThinkingEntries(interrupted) {
  thinkingEntries.value.forEach(entry => {
    if (!entry.done) {
      entry.done = true
      entry.interrupted = interrupted
    }
  })
}

function thinkingLabel(step) {
  const normalized = String(step)
  if (normalized.startsWith('argument_chain_map_')) {
    const index = Number(normalized.slice('argument_chain_map_'.length))
    return Number.isFinite(index) ? `提取第 ${index + 1} 个文档片段的论据` : '提取文档片段中的论据'
  }
  if (normalized === 'argument_chain_reduce') return '整理完整论据链'
  if (normalized === 'logic_flaws') return '检查论据中的逻辑漏洞'
  if (normalized.startsWith('cross_validation_')) {
    const index = Number(normalized.slice('cross_validation_'.length))
    return Number.isFinite(index) ? `交叉验证第 ${index + 1} 条论据` : '交叉验证论据'
  }
  return '分析依据'
}

function thinkingStatus(entry) {
  if (entry.done) return entry.interrupted ? '已结束' : '已完成'
  return entry.step === thinkingStep.value ? '进行中' : '等待中'
}

function stageStatus(stage) {
  if (stage.done) return '已完成'
  return stage.key === streamStep.value ? '进行中' : '等待中'
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

      <div v-if="currentTask.status === 'COMPLETED'" class="flex gap-1 border-b border-border mb-6 gs-task-reveal">
        <button
          v-for="(tab, i) in tabs" :key="tab"
          class="px-4 py-2 text-sm transition-colors duration-200 cursor-pointer"
          :class="activeTab === i ? 'text-primary border-b-2 border-primary font-medium' : 'text-text-muted hover:text-primary'"
          @click="activeTab = i"
        >
          {{ tab }}
        </button>
      </div>

      <div v-if="streamStages.length || thinkingEntries.length" class="mb-6 border-y border-border">
        <button
          type="button"
          class="flex w-full items-center justify-between gap-3 py-3 text-left text-sm transition-colors hover:text-primary"
          :aria-expanded="analysisProcessExpanded"
          aria-controls="analysis-process-panel"
          @click="analysisProcessExpanded = !analysisProcessExpanded"
        >
          <span class="font-medium text-text">分析过程</span>
          <span class="ml-auto text-xs text-text-muted">
            {{ analysisProcessExpanded ? '收起过程' : (isActiveTask ? `${streamStages.length} 个阶段记录` : `${streamStages.length} 个阶段已完成`) }}
          </span>
          <ChevronDown class="h-4 w-4 shrink-0 text-text-muted transition-transform" :class="{ 'rotate-180': analysisProcessExpanded }" />
        </button>

        <div
          v-show="analysisProcessExpanded"
          id="analysis-process-panel"
          data-lenis-prevent
          class="max-h-[32rem] space-y-5 overflow-y-auto pb-4"
        >
          <div v-for="stage in streamStages" :key="stage.key" class="border-l-2 border-border pl-4">
            <div class="mb-2 flex items-start justify-between gap-3 text-xs">
              <span class="min-w-0 flex-1 font-medium text-text" :title="stage.summary">{{ stage.summary }}</span>
              <span class="shrink-0" :class="stage.done ? 'text-green-600' : 'text-accent'">{{ stageStatus(stage) }}</span>
            </div>
            <div class="mt-2 h-1 overflow-hidden rounded-full bg-gray-100">
              <div class="h-full rounded-full bg-accent transition-all duration-500" :style="{ width: `${stage.progress}%` }" />
            </div>
          </div>

          <div v-if="thinkingEntries.length" class="border-t border-border pt-4">
            <div class="mb-3 text-xs font-medium text-text">公开分析依据</div>
            <div v-for="entry in thinkingEntries" :key="entry.step" class="mb-4 last:mb-0 border-l-2 border-accent/40 pl-4">
              <div class="mb-2 flex items-start justify-between gap-3 text-xs">
                <span class="min-w-0 flex-1 font-medium text-text">{{ thinkingLabel(entry.step) }}</span>
                <span class="shrink-0" :class="entry.done && !entry.interrupted ? 'text-green-600' : 'text-accent'">{{ thinkingStatus(entry) }}</span>
              </div>
              <p class="whitespace-pre-wrap break-words text-sm leading-6 text-text-muted">{{ entry.text || '正在整理分析依据...' }}</p>
            </div>
          </div>
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
