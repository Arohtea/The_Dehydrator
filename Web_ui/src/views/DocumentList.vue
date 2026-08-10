<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useDocumentStore } from '@/stores/document'
import { storeToRefs } from 'pinia'
import { FileText, Zap, Telescope, Trash2 } from 'lucide-vue-next'
import gsap from 'gsap'

const router = useRouter()
const store = useDocumentStore()
const { documents, loading, referenceLibraries } = storeToRefs(store)

const containerRef = ref(null)
const docPendingDelete = ref(null)
const deleting = ref(false)
const analysisDialogVisible = ref(false)
const analysisTargetDoc = ref(null)
const analysisMode = ref('deep')
const selectedLibraryIds = ref([])
const startingAnalysis = ref(false)
let statusRefreshTimer = null

const activeAnalysisStatuses = new Set(['PENDING', 'PROCESSING', 'CANCELLING'])

const analysisModeLabel = computed(() => analysisMode.value === 'quick' ? '快速分析' : '深度分析')

onMounted(async () => {
  await Promise.all([store.fetchDocuments(), store.fetchReferenceLibraries()])
  statusRefreshTimer = setInterval(refreshActiveDocuments, 3000)
})

onUnmounted(() => {
  if (statusRefreshTimer) clearInterval(statusRefreshTimer)
})

watch(loading, async (isLoading) => {
  if (isLoading) return
  await nextTick()
  animateItems()
})

function animateItems() {
  if (containerRef.value) {
    const items = containerRef.value.querySelectorAll('.gs-doc-item')
    if (items.length) {
      gsap.fromTo(items,
        { opacity: 0, y: 20 },
        { opacity: 1, y: 0, stagger: 0.1, duration: 0.6, ease: 'power2.out', overwrite: 'auto' }
      )
    }
  }
}

function openAnalysisDialog(doc, mode) {
  if (analysisUnavailable(doc)) return
  analysisTargetDoc.value = doc
  analysisMode.value = mode
  selectedLibraryIds.value = []
  analysisDialogVisible.value = true
}

function toggleLibrarySelection(libraryId) {
  if (selectedLibraryIds.value.includes(libraryId)) {
    selectedLibraryIds.value = selectedLibraryIds.value.filter(id => id !== libraryId)
    return
  }
  selectedLibraryIds.value = [...selectedLibraryIds.value, libraryId]
}

async function confirmStartAnalysis() {
  if (!analysisTargetDoc.value || startingAnalysis.value || analysisUnavailable(analysisTargetDoc.value)) return
  startingAnalysis.value = true
  try {
    const task = await store.startAnalysis(
      analysisTargetDoc.value.id,
      analysisMode.value,
      selectedLibraryIds.value
    )
    if (task.error) {
      alert(task.error)
      return
    }
    analysisDialogVisible.value = false
    router.push(`/documents/${analysisTargetDoc.value.id}`)
  } catch (error) {
    alert(error?.response?.data?.error || '启动分析失败')
  } finally {
    startingAnalysis.value = false
  }
}

function cancelStartAnalysis() {
  if (startingAnalysis.value) return
  analysisDialogVisible.value = false
  analysisTargetDoc.value = null
  selectedLibraryIds.value = []
}

function askRemove(doc) {
  docPendingDelete.value = doc
}

function cancelRemove() {
  if (deleting.value) return
  docPendingDelete.value = null
}

async function confirmRemove() {
  if (!docPendingDelete.value || deleting.value) return
  deleting.value = true
  try {
    await store.removeDocument(docPendingDelete.value.id)
    docPendingDelete.value = null
  } catch (error) {
    alert(error?.response?.data?.error || '删除失败，文档和资源已保留')
    await store.fetchDocuments()
  } finally {
    deleting.value = false
  }
}

function isAnalysisActive(doc) {
  return activeAnalysisStatuses.has(doc.analysisStatus)
}

function analysisUnavailable(doc) {
  return doc.deleting || doc.vectorStatus === 'PROCESSING' || isAnalysisActive(doc)
}

function statusLabel(doc) {
  if (doc.deleting) return '删除中'
  if (doc.vectorStatus === 'PROCESSING') return '向量化中'
  const labels = {
    PENDING: '等待分析',
    PROCESSING: '分析中',
    CANCELLING: '终止中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  return labels[doc.analysisStatus] || '待分析'
}

function statusClass(doc) {
  if (doc.deleting || doc.analysisStatus === 'CANCELLING') return 'bg-amber-50 text-amber-700'
  if (doc.vectorStatus === 'PROCESSING' || isAnalysisActive(doc)) return 'bg-blue-50 text-blue-700'
  if (doc.analysisStatus === 'COMPLETED') return 'bg-green-50 text-green-700'
  if (doc.analysisStatus === 'FAILED') return 'bg-red-50 text-red-700'
  return 'bg-gray-100 text-text-muted'
}

async function refreshActiveDocuments() {
  if (loading.value || !documents.value.some(doc => analysisUnavailable(doc))) return
  try {
    await store.fetchDocuments({ silent: true })
  } catch {
    // 列表已有内容时，短暂刷新失败不覆盖当前状态。
  }
}
</script>

<template>
  <div ref="containerRef">
    <h1 class="font-heading text-2xl font-bold mb-6 gs-doc-item opacity-0">文档列表</h1>

    <div v-if="loading" class="space-y-4">
      <div v-for="i in 3" :key="i" class="h-20 bg-white border border-border rounded-lg animate-pulse gs-doc-item opacity-0"></div>
    </div>

    <div v-else-if="documents.length === 0" class="text-center py-20 text-text-muted gs-doc-item opacity-0">
      暂无文档，请先上传
    </div>

    <div v-else class="space-y-3">
      <div
        v-for="doc in documents" :key="doc.id"
        class="bg-white border border-border rounded-lg p-4 flex items-center justify-between hover:border-primary-light transition-colors duration-200 cursor-pointer gs-doc-item opacity-0"
        @click="router.push(`/documents/${doc.id}`)"
      >
        <div class="flex items-center gap-3">
          <FileText class="w-5 h-5 text-primary-light shrink-0" />
          <div>
            <p class="font-medium">{{ doc.filename }}</p>
            <p class="text-xs text-text-muted">{{ new Date(doc.createdAt).toLocaleString() }}</p>
            <div class="mt-2 flex items-center gap-2 flex-wrap">
              <span class="rounded-full px-2.5 py-1 text-xs" :class="statusClass(doc)">
                {{ statusLabel(doc) }}
              </span>
              <span v-if="isAnalysisActive(doc)" class="text-xs text-text-muted">
                {{ doc.analysisCurrentStep || '准备中' }} · {{ doc.analysisProgress || 0 }}%
              </span>
            </div>
            <div v-if="isAnalysisActive(doc)" class="mt-2 h-1.5 w-64 max-w-full overflow-hidden rounded-full bg-gray-100">
              <div class="h-full rounded-full bg-primary transition-all duration-500" :style="{ width: `${doc.analysisProgress || 0}%` }" />
            </div>
          </div>
        </div>
        <div class="flex items-center gap-2">
          <button
            @click.stop="openAnalysisDialog(doc, 'quick')"
            :disabled="analysisUnavailable(doc)"
            class="flex items-center gap-1.5 px-3 py-1.5 bg-accent text-white text-sm rounded-lg hover:bg-accent/90 transition-colors duration-200 cursor-pointer"
            :class="{ 'cursor-not-allowed opacity-50': analysisUnavailable(doc) }"
          >
            <Zap class="w-3.5 h-3.5" />
            快速分析
          </button>
          <button
            @click.stop="openAnalysisDialog(doc, 'deep')"
            :disabled="analysisUnavailable(doc)"
            class="flex items-center gap-1.5 px-3 py-1.5 bg-primary text-white text-sm rounded-lg hover:bg-primary-light transition-colors duration-200 cursor-pointer"
            :class="{ 'cursor-not-allowed opacity-50': analysisUnavailable(doc) }"
          >
            <Telescope class="w-3.5 h-3.5" />
            深度分析
          </button>
          <button
            @click.stop="askRemove(doc)"
            class="flex items-center gap-1.5 px-3 py-1.5 bg-red-500 text-white text-sm rounded-lg hover:bg-red-600 transition-colors duration-200 cursor-pointer"
          >
            <Trash2 class="w-3.5 h-3.5" />
            删除
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="analysisDialogVisible"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
      @click.self="cancelStartAnalysis"
    >
      <div class="w-full max-w-xl rounded-xl border border-border bg-white p-6 shadow-xl">
        <h2 class="font-heading text-lg font-semibold text-text">{{ analysisModeLabel }}前选择参考资料</h2>
        <p v-if="analysisMode === 'quick'" class="mt-3 text-sm leading-relaxed text-text-muted">
          当前文档：{{ analysisTargetDoc?.filename }}。快速分析会基于模型自身知识进行交叉验证，不会用当前论文验证当前论文；如有需要，可额外选择资料集作为参考。
        </p>
        <p v-else class="mt-3 text-sm leading-relaxed text-text-muted">
          当前文档：{{ analysisTargetDoc?.filename }}。深度分析会结合模型自身知识、参考资料和联网搜索进行交叉验证，不会用当前论文验证当前论文；也可以不选资料集直接开始。
        </p>

        <div class="mt-5 space-y-3 max-h-80 overflow-y-auto pr-1">
          <button
            type="button"
            @click="selectedLibraryIds = []"
            class="w-full rounded-lg border border-dashed border-border px-4 py-3 text-left text-sm transition-colors hover:border-primary hover:bg-primary/5"
            :class="selectedLibraryIds.length === 0 ? 'border-primary bg-primary/5 text-primary' : 'text-text-muted'"
          >
            不使用参考资料，直接分析
          </button>

          <div v-if="referenceLibraries.length === 0" class="rounded-lg border border-border bg-gray-50 px-4 py-6 text-sm text-text-muted">
            暂无资料集，可先到“资料库”页面创建并上传参考文件。
          </div>

          <button
            v-for="library in referenceLibraries"
            :key="library.id"
            type="button"
            @click="toggleLibrarySelection(library.id)"
            class="w-full rounded-lg border px-4 py-3 text-left transition-colors"
            :class="selectedLibraryIds.includes(library.id)
              ? 'border-primary bg-primary/5'
              : 'border-border hover:border-primary-light hover:bg-gray-50'"
          >
            <div class="flex items-center justify-between gap-3">
              <div>
                <p class="font-medium text-text">{{ library.name }}</p>
                <p class="mt-1 text-xs text-text-muted">{{ new Date(library.createdAt).toLocaleString() }}</p>
              </div>
              <span
                class="rounded-full px-2.5 py-1 text-xs"
                :class="selectedLibraryIds.includes(library.id) ? 'bg-primary text-white' : 'bg-gray-100 text-text-muted'"
              >
                {{ selectedLibraryIds.includes(library.id) ? '已选择' : '未选择' }}
              </span>
            </div>
          </button>
        </div>

        <div class="mt-6 flex justify-end gap-3">
          <button
            @click="cancelStartAnalysis"
            :disabled="startingAnalysis"
            class="rounded-lg border border-border px-4 py-2 text-sm text-text-muted transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            取消
          </button>
          <button
            @click="confirmStartAnalysis"
            :disabled="startingAnalysis"
            class="rounded-lg bg-primary px-4 py-2 text-sm text-white transition-colors hover:bg-primary-light disabled:cursor-not-allowed disabled:opacity-50"
          >
            {{ startingAnalysis ? '启动中...' : `开始${analysisModeLabel}` }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="docPendingDelete"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4"
      @click.self="cancelRemove">
      <div class="w-full max-w-sm rounded-xl border border-border bg-white p-6 shadow-xl">
        <h2 class="font-heading text-lg font-semibold text-text">确认删除</h2>
        <p class="mt-3 text-sm leading-relaxed text-text-muted">
          <template v-if="isAnalysisActive(docPendingDelete)">
            「{{ docPendingDelete.filename }}」正在分析。确认后会先终止分析，收到终止确认后才删除文档及相关资源。
          </template>
          <template v-else>
            确定删除「{{ docPendingDelete.filename }}」？删除后将同时移除文档记录和分析结果。
          </template>
        </p>
        <div class="mt-6 flex justify-end gap-3">
          <button
            @click="cancelRemove"
            :disabled="deleting"
            class="rounded-lg border border-border px-4 py-2 text-sm text-text-muted transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            取消
          </button>
          <button
            @click="confirmRemove"
            :disabled="deleting"
            class="rounded-lg bg-red-500 px-4 py-2 text-sm text-white transition-colors hover:bg-red-600 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {{ deleting ? (isAnalysisActive(docPendingDelete) ? '终止并删除中...' : '删除中...') : (isAnalysisActive(docPendingDelete) ? '终止并删除' : '确认删除') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
