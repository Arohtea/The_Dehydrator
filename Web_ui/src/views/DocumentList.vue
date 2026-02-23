<script setup>
import { ref, onMounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useDocumentStore } from '@/stores/document'
import { storeToRefs } from 'pinia'
import { FileText, Play, Trash2 } from 'lucide-vue-next'
import gsap from 'gsap'

const router = useRouter()
const store = useDocumentStore()
const { documents, loading } = storeToRefs(store)

const containerRef = ref(null)

onMounted(async () => {
  await store.fetchDocuments()
  animateItems()
})

watch(loading, async (newVal) => {
  if (!newVal) {
    await nextTick()
    animateItems()
  }
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

async function analyze(doc) {
  const task = await store.startAnalysis(doc.id)
  if (task.error) {
    alert(task.error)
    return
  }
  router.push(`/documents/${doc.id}`)
}

async function remove(doc) {
  if (!confirm(`确定删除「${doc.filename}」？`)) return
  await store.removeDocument(doc.id)
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
          </div>
        </div>
        <div class="flex items-center gap-2">
          <button
            @click.stop="analyze(doc)"
            class="flex items-center gap-1.5 px-3 py-1.5 bg-primary text-white text-sm rounded-lg hover:bg-primary-light transition-colors duration-200 cursor-pointer"
          >
            <Play class="w-3.5 h-3.5" />
            分析
          </button>
          <button
            @click.stop="remove(doc)"
            class="flex items-center gap-1.5 px-3 py-1.5 bg-red-500 text-white text-sm rounded-lg hover:bg-red-600 transition-colors duration-200 cursor-pointer"
          >
            <Trash2 class="w-3.5 h-3.5" />
            删除
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
