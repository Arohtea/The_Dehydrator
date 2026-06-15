<script setup>
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { BookOpen, FolderPlus, Trash2, Upload, FileText } from 'lucide-vue-next'
import { useDocumentStore } from '@/stores/document'

const store = useDocumentStore()
const { referenceLibraries, referenceDocuments } = storeToRefs(store)

const creating = ref(false)
const createName = ref('')
const selectedLibraryId = ref('')
const loadingDocuments = ref(false)
const uploading = ref(false)
const deletingLibraryId = ref('')
const deletingDocumentId = ref('')

const activeLibrary = computed(() =>
  referenceLibraries.value.find(item => item.id === selectedLibraryId.value) || null
)

onMounted(async () => {
  const libraries = await store.fetchReferenceLibraries()
  if (libraries.length) {
    await selectLibrary(libraries[0].id)
  }
})

async function createLibrary() {
  if (!createName.value.trim() || creating.value) return
  creating.value = true
  try {
    const library = await store.createReferenceLibrary(createName.value.trim())
    createName.value = ''
    await selectLibrary(library.id)
  } catch (error) {
    alert(error?.response?.data?.error || '创建资料集失败')
  } finally {
    creating.value = false
  }
}

async function selectLibrary(libraryId) {
  selectedLibraryId.value = libraryId
  loadingDocuments.value = true
  try {
    await store.fetchReferenceDocuments(libraryId)
  } finally {
    loadingDocuments.value = false
  }
}

async function uploadFiles(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || !selectedLibraryId.value || uploading.value) return
  uploading.value = true
  try {
    await store.uploadReferenceFile(selectedLibraryId.value, file)
    await store.fetchReferenceDocuments(selectedLibraryId.value)
  } catch (error) {
    alert(error?.response?.data?.error || '上传参考资料失败')
  } finally {
    uploading.value = false
  }
}

async function removeLibrary(library) {
  if (!confirm(`确定删除资料集「${library.name}」？仅支持删除空资料集。`) || deletingLibraryId.value) return
  deletingLibraryId.value = library.id
  try {
    await store.removeReferenceLibrary(library.id)
    if (selectedLibraryId.value === library.id) {
      const next = referenceLibraries.value[0]
      if (next) {
        await selectLibrary(next.id)
      } else {
        selectedLibraryId.value = ''
        referenceDocuments.value = []
      }
    }
  } catch (error) {
    alert(error?.response?.data?.error || '删除资料集失败')
  } finally {
    deletingLibraryId.value = ''
  }
}

async function removeDocument(doc) {
  if (!confirm(`确定删除参考文件「${doc.filename}」？`) || deletingDocumentId.value) return
  deletingDocumentId.value = doc.id
  try {
    await store.removeReferenceDocument(doc.id)
  } catch (error) {
    alert(error?.response?.data?.error || '删除参考文件失败')
  } finally {
    deletingDocumentId.value = ''
  }
}
</script>

<template>
  <div class="grid gap-6 lg:grid-cols-[320px_minmax(0,1fr)]">
    <section class="rounded-2xl border border-border bg-white p-5">
      <div class="flex items-center gap-2">
        <BookOpen class="h-5 w-5 text-primary" />
        <h1 class="font-heading text-xl font-semibold text-text">资料库</h1>
      </div>

      <div class="mt-5 flex gap-2">
        <input
          v-model="createName"
          type="text"
          placeholder="输入资料集名称"
          class="min-w-0 flex-1 rounded-lg border border-border px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
          @keyup.enter="createLibrary"
        />
        <button
          @click="createLibrary"
          :disabled="creating"
          class="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm text-white transition-colors hover:bg-primary-light disabled:cursor-not-allowed disabled:opacity-50"
        >
          <FolderPlus class="h-4 w-4" />
          {{ creating ? '创建中' : '新建' }}
        </button>
      </div>

      <div class="mt-5 space-y-3">
        <button
          v-for="library in referenceLibraries"
          :key="library.id"
          class="w-full rounded-xl border px-4 py-3 text-left transition-colors"
          :class="selectedLibraryId === library.id ? 'border-primary bg-primary/5' : 'border-border hover:border-primary-light hover:bg-gray-50'"
          @click="selectLibrary(library.id)"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <p class="truncate font-medium text-text">{{ library.name }}</p>
              <p class="mt-1 text-xs text-text-muted">{{ new Date(library.createdAt).toLocaleString() }}</p>
            </div>
            <button
              class="rounded-lg p-1 text-red-500 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
              :disabled="deletingLibraryId === library.id"
              @click.stop="removeLibrary(library)"
            >
              <Trash2 class="h-4 w-4" />
            </button>
          </div>
        </button>

        <div v-if="referenceLibraries.length === 0" class="rounded-xl border border-dashed border-border px-4 py-10 text-center text-sm text-text-muted">
          暂无资料集
        </div>
      </div>
    </section>

    <section class="rounded-2xl border border-border bg-white p-5">
      <div v-if="!activeLibrary" class="py-24 text-center text-sm text-text-muted">
        请选择或新建一个资料集
      </div>

      <template v-else>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 class="font-heading text-xl font-semibold text-text">{{ activeLibrary.name }}</h2>
            <p class="mt-1 text-sm text-text-muted">上传参考文档后，这些内容仅参与交叉验证阶段的 RAG 检索。</p>
          </div>

          <label class="inline-flex cursor-pointer items-center gap-2 rounded-lg bg-accent px-4 py-2 text-sm text-white transition-colors hover:bg-accent/90">
            <Upload class="h-4 w-4" />
            {{ uploading ? '上传中...' : '上传资料' }}
            <input type="file" accept=".pdf,.docx,.txt" class="hidden" :disabled="uploading" @change="uploadFiles" />
          </label>
        </div>

        <div v-if="loadingDocuments" class="mt-6 space-y-3">
          <div v-for="i in 3" :key="i" class="h-18 rounded-xl border border-border bg-gray-50 animate-pulse"></div>
        </div>

        <div v-else-if="referenceDocuments.length === 0" class="mt-6 rounded-xl border border-dashed border-border px-4 py-16 text-center text-sm text-text-muted">
          该资料集暂无文件
        </div>

        <div v-else class="mt-6 space-y-3">
          <div
            v-for="doc in referenceDocuments"
            :key="doc.id"
            class="flex items-center justify-between rounded-xl border border-border px-4 py-3"
          >
            <div class="flex min-w-0 items-center gap-3">
              <FileText class="h-4 w-4 shrink-0 text-primary" />
              <div class="min-w-0">
                <p class="truncate font-medium text-text">{{ doc.filename }}</p>
                <p class="mt-1 text-xs text-text-muted">
                  {{ new Date(doc.createdAt).toLocaleString() }} · {{ doc.aiDocId ? '已向量化' : '向量化中' }}
                </p>
              </div>
            </div>
            <button
              class="rounded-lg p-2 text-red-500 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
              :disabled="deletingDocumentId === doc.id"
              @click="removeDocument(doc)"
            >
              <Trash2 class="h-4 w-4" />
            </button>
          </div>
        </div>
      </template>
    </section>
  </div>
</template>
