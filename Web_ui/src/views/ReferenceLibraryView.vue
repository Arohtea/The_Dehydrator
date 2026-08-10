<script setup>
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import {
  BookOpen,
  FolderPlus,
  Tags,
  Trash2,
  Upload,
  FileText,
  Save,
  PencilLine,
  ShieldCheck,
} from 'lucide-vue-next'
import { useDocumentStore } from '@/stores/document'

const store = useDocumentStore()
const {
  referenceLibraries,
  referenceDocuments,
  referenceFolders,
  referenceCategories,
} = storeToRefs(store)

const creatingLibrary = ref(false)
const createLibraryName = ref('')
const selectedLibraryId = ref('')
const loadingLibraryData = ref(false)
const uploading = ref(false)
const deletingLibraryId = ref('')
const deletingDocumentId = ref('')
const savingDocumentId = ref('')
const creatingFolder = ref(false)
const creatingCategory = ref(false)
const createFolderName = ref('')
const createCategoryName = ref('')
const renamingFolderId = ref('')
const renamingCategoryId = ref('')
const deletingFolderId = ref('')
const deletingCategoryId = ref('')
let librarySelectionToken = 0

const activeLibrary = computed(() =>
  referenceLibraries.value.find(item => item.id === selectedLibraryId.value) || null
)

onMounted(async () => {
  const libraries = await store.fetchReferenceLibraries()
  if (libraries.length) {
    await selectLibrary(libraries[0].id)
  }
})

async function selectLibrary(libraryId) {
  const selectionToken = ++librarySelectionToken
  selectedLibraryId.value = libraryId
  loadingLibraryData.value = true
  try {
    const [documents, folders, categories] = await Promise.all([
      store.fetchReferenceDocuments(libraryId, { commit: false }),
      store.fetchReferenceFolders(libraryId, { commit: false }),
      store.fetchReferenceCategories(libraryId, { commit: false }),
    ])
    if (selectionToken !== librarySelectionToken || selectedLibraryId.value !== libraryId) {
      return
    }
    referenceDocuments.value = documents
    referenceFolders.value = folders
    referenceCategories.value = categories
  } catch (error) {
    if (selectionToken !== librarySelectionToken) {
      return
    }
    alert(error?.response?.data?.error || '加载资料库失败')
  } finally {
    if (selectionToken === librarySelectionToken) {
      loadingLibraryData.value = false
    }
  }
}

async function createLibrary() {
  if (!createLibraryName.value.trim() || creatingLibrary.value) return
  creatingLibrary.value = true
  try {
    const library = await store.createReferenceLibrary(createLibraryName.value.trim())
    createLibraryName.value = ''
    await selectLibrary(library.id)
  } catch (error) {
    alert(error?.response?.data?.error || '创建资料集失败')
  } finally {
    creatingLibrary.value = false
  }
}

async function removeLibrary(library) {
  if (library.systemKey) {
    alert('系统资料库不允许删除')
    return
  }
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
        referenceFolders.value = []
        referenceCategories.value = []
      }
    }
  } catch (error) {
    alert(error?.response?.data?.error || '删除资料集失败')
  } finally {
    deletingLibraryId.value = ''
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

async function createFolder() {
  if (!selectedLibraryId.value || !createFolderName.value.trim() || creatingFolder.value) return
  creatingFolder.value = true
  try {
    await store.createReferenceFolder(selectedLibraryId.value, createFolderName.value.trim())
    createFolderName.value = ''
  } catch (error) {
    alert(error?.response?.data?.error || '创建文件夹失败')
  } finally {
    creatingFolder.value = false
  }
}

async function createCategory() {
  if (!selectedLibraryId.value || !createCategoryName.value.trim() || creatingCategory.value) return
  creatingCategory.value = true
  try {
    await store.createReferenceCategory(selectedLibraryId.value, createCategoryName.value.trim())
    createCategoryName.value = ''
  } catch (error) {
    alert(error?.response?.data?.error || '创建分类失败')
  } finally {
    creatingCategory.value = false
  }
}

async function renameFolder(folder) {
  if (!folder.name?.trim() || renamingFolderId.value) return
  renamingFolderId.value = folder.id
  try {
    await store.updateReferenceFolder(folder.id, folder.name.trim())
  } catch (error) {
    alert(error?.response?.data?.error || '重命名文件夹失败')
    await store.fetchReferenceFolders(selectedLibraryId.value)
  } finally {
    renamingFolderId.value = ''
  }
}

async function renameCategory(category) {
  if (!category.name?.trim() || renamingCategoryId.value) return
  renamingCategoryId.value = category.id
  try {
    await store.updateReferenceCategory(category.id, category.name.trim())
  } catch (error) {
    alert(error?.response?.data?.error || '重命名分类失败')
    await store.fetchReferenceCategories(selectedLibraryId.value)
  } finally {
    renamingCategoryId.value = ''
  }
}

async function removeFolder(folder) {
  if (!confirm(`确定删除文件夹「${folder.name}」？`) || deletingFolderId.value) return
  deletingFolderId.value = folder.id
  try {
    await store.removeReferenceFolder(folder.id)
    await store.fetchReferenceDocuments(selectedLibraryId.value)
  } catch (error) {
    alert(error?.response?.data?.error || '删除文件夹失败')
  } finally {
    deletingFolderId.value = ''
  }
}

async function removeCategory(category) {
  if (!confirm(`确定删除分类「${category.name}」？`) || deletingCategoryId.value) return
  deletingCategoryId.value = category.id
  try {
    await store.removeReferenceCategory(category.id)
    await store.fetchReferenceDocuments(selectedLibraryId.value)
  } catch (error) {
    alert(error?.response?.data?.error || '删除分类失败')
  } finally {
    deletingCategoryId.value = ''
  }
}

async function saveDocument(doc) {
  if (savingDocumentId.value) return
  savingDocumentId.value = doc.id
  try {
    await store.updateReferenceDocument(doc.id, {
      displayName: doc.displayName,
      folderId: doc.folderId || '',
      categoryId: doc.categoryId || '',
    })
  } catch (error) {
    alert(error?.response?.data?.error || '保存资料属性失败')
    await store.fetchReferenceDocuments(selectedLibraryId.value)
  } finally {
    savingDocumentId.value = ''
  }
}

async function removeDocument(doc) {
  if (!confirm(`确定删除参考文件「${doc.displayName || doc.filename}」？`) || deletingDocumentId.value) return
  deletingDocumentId.value = doc.id
  try {
    await store.removeReferenceDocument(doc.id)
    await store.fetchReferenceDocuments(selectedLibraryId.value)
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
          v-model="createLibraryName"
          type="text"
          placeholder="输入资料集名称"
          class="min-w-0 flex-1 rounded-lg border border-border px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
          @keyup.enter="createLibrary"
        />
        <button
          @click="createLibrary"
          :disabled="creatingLibrary"
          class="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm text-white transition-colors hover:bg-primary-light disabled:cursor-not-allowed disabled:opacity-50"
        >
          <FolderPlus class="h-4 w-4" />
          {{ creatingLibrary ? '创建中' : '新建' }}
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
              <div class="flex flex-wrap items-center gap-2">
                <p class="truncate font-medium text-text">{{ library.name }}</p>
                <span
                  v-if="library.systemKey"
                  class="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-xs text-primary"
                >
                  <ShieldCheck class="h-3 w-3" />
                  系统
                </span>
              </div>
              <p class="mt-1 text-xs text-text-muted">{{ new Date(library.createdAt).toLocaleString() }}</p>
            </div>
            <button
              v-if="!library.systemKey"
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
        <div class="flex flex-wrap items-center justify-between gap-3 border-b border-border pb-5">
          <div>
            <div class="flex flex-wrap items-center gap-2">
              <h2 class="font-heading text-xl font-semibold text-text">{{ activeLibrary.name }}</h2>
              <span
                v-if="activeLibrary.systemKey"
                class="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2.5 py-1 text-xs text-primary"
              >
                <ShieldCheck class="h-3 w-3" />
                自动归档
              </span>
            </div>
            <p class="mt-1 text-sm text-text-muted">
              所有资料都可编辑名称、文件夹和分类。分析上传的论文会自动进入系统资料库，并由 AI 完成初步归类。
            </p>
          </div>

          <label class="inline-flex cursor-pointer items-center gap-2 rounded-lg bg-accent px-4 py-2 text-sm text-white transition-colors hover:bg-accent/90">
            <Upload class="h-4 w-4" />
            {{ uploading ? '上传中...' : '上传资料' }}
            <input type="file" accept=".pdf,.docx,.txt" class="hidden" :disabled="uploading" @change="uploadFiles" />
          </label>
        </div>

        <div v-if="loadingLibraryData" class="mt-6 space-y-3">
          <div v-for="i in 4" :key="i" class="h-18 rounded-xl border border-border bg-gray-50 animate-pulse"></div>
        </div>

        <div v-else class="mt-6 grid gap-8 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <section>
            <div class="flex items-center gap-2">
              <FolderPlus class="h-4 w-4 text-primary" />
              <h3 class="text-sm font-semibold text-text">文件夹</h3>
            </div>
            <div class="mt-3 flex gap-2">
              <input
                v-model="createFolderName"
                type="text"
                placeholder="新增文件夹"
                class="min-w-0 flex-1 rounded-lg border border-border px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                @keyup.enter="createFolder"
              />
              <button
                @click="createFolder"
                :disabled="creatingFolder"
                class="rounded-lg bg-primary px-3 py-2 text-xs text-white transition-colors hover:bg-primary-light disabled:cursor-not-allowed disabled:opacity-50"
              >
                新增
              </button>
            </div>

            <div class="mt-4 space-y-2">
              <div
                v-for="folder in referenceFolders"
                :key="folder.id"
                class="rounded-xl border border-border px-3 py-3"
              >
                <div class="flex items-center gap-2">
                  <input
                    v-model="folder.name"
                    type="text"
                    class="min-w-0 flex-1 rounded-lg border border-border px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    @keyup.enter="renameFolder(folder)"
                  />
                  <button
                    class="rounded-lg p-2 text-primary transition-colors hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
                    :disabled="renamingFolderId === folder.id"
                    @click="renameFolder(folder)"
                  >
                    <Save class="h-4 w-4" />
                  </button>
                  <button
                    class="rounded-lg p-2 text-red-500 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
                    :disabled="deletingFolderId === folder.id"
                    @click="removeFolder(folder)"
                  >
                    <Trash2 class="h-4 w-4" />
                  </button>
                </div>
              </div>

              <div v-if="referenceFolders.length === 0" class="rounded-xl border border-dashed border-border px-4 py-8 text-center text-sm text-text-muted">
                暂无文件夹
              </div>
            </div>
          </section>

          <section>
            <div class="flex items-center gap-2">
              <Tags class="h-4 w-4 text-accent" />
              <h3 class="text-sm font-semibold text-text">分类</h3>
            </div>
            <div class="mt-3 flex gap-2">
              <input
                v-model="createCategoryName"
                type="text"
                placeholder="新增分类"
                class="min-w-0 flex-1 rounded-lg border border-border px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                @keyup.enter="createCategory"
              />
              <button
                @click="createCategory"
                :disabled="creatingCategory"
                class="rounded-lg bg-accent px-3 py-2 text-xs text-white transition-colors hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
              >
                新增
              </button>
            </div>

            <div class="mt-4 space-y-2">
              <div
                v-for="category in referenceCategories"
                :key="category.id"
                class="rounded-xl border border-border px-3 py-3"
              >
                <div class="flex items-center gap-2">
                  <input
                    v-model="category.name"
                    type="text"
                    class="min-w-0 flex-1 rounded-lg border border-border px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    @keyup.enter="renameCategory(category)"
                  />
                  <button
                    class="rounded-lg p-2 text-primary transition-colors hover:bg-primary/10 disabled:cursor-not-allowed disabled:opacity-50"
                    :disabled="renamingCategoryId === category.id"
                    @click="renameCategory(category)"
                  >
                    <Save class="h-4 w-4" />
                  </button>
                  <button
                    class="rounded-lg p-2 text-red-500 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
                    :disabled="deletingCategoryId === category.id"
                    @click="removeCategory(category)"
                  >
                    <Trash2 class="h-4 w-4" />
                  </button>
                </div>
              </div>

              <div v-if="referenceCategories.length === 0" class="rounded-xl border border-dashed border-border px-4 py-8 text-center text-sm text-text-muted">
                暂无分类
              </div>
            </div>
          </section>

          <section class="xl:col-span-2 min-w-0">
            <div class="flex items-center gap-2">
              <FileText class="h-4 w-4 text-primary-light" />
              <h3 class="text-sm font-semibold text-text">资料文件</h3>
            </div>

            <div v-if="referenceDocuments.length === 0" class="mt-4 rounded-xl border border-dashed border-border px-4 py-16 text-center text-sm text-text-muted">
              该资料集暂无文件
            </div>

            <div v-else class="mt-4 space-y-3">
              <div
                v-for="doc in referenceDocuments"
                :key="doc.id"
                class="rounded-xl border border-border px-4 py-4"
              >
                <div class="flex flex-wrap items-start justify-between gap-3">
                  <div class="min-w-0">
                    <div class="flex flex-wrap items-center gap-2">
                      <p class="truncate font-medium text-text">{{ doc.displayName || doc.filename }}</p>
                      <span class="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-text-muted">
                        {{ doc.aiDocId ? '已向量化' : '向量化中' }}
                      </span>
                    </div>
                    <p class="mt-1 text-xs text-text-muted">
                      原文件：{{ doc.filename }} · {{ new Date(doc.createdAt).toLocaleString() }}
                    </p>
                  </div>

                  <button
                    v-if="!doc.sourceDocumentId"
                    class="rounded-lg p-2 text-red-500 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
                    :disabled="deletingDocumentId === doc.id"
                    @click="removeDocument(doc)"
                  >
                    <Trash2 class="h-4 w-4" />
                  </button>
                </div>

                <div class="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-[minmax(0,1.2fr)_160px_160px_auto]">
                  <label class="min-w-0">
                    <span class="mb-1 block text-xs text-text-muted">展示名称</span>
                    <div class="flex items-center gap-2">
                      <PencilLine class="h-4 w-4 shrink-0 text-text-muted" />
                      <input
                        v-model="doc.displayName"
                        type="text"
                        class="min-w-0 flex-1 rounded-lg border border-border px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                      />
                    </div>
                  </label>

                  <label class="min-w-0">
                    <span class="mb-1 block text-xs text-text-muted">文件夹</span>
                    <select
                      v-model="doc.folderId"
                      class="w-full rounded-lg border border-border px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    >
                      <option value="">未设置</option>
                      <option v-for="folder in referenceFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
                    </select>
                  </label>

                  <label class="min-w-0">
                    <span class="mb-1 block text-xs text-text-muted">分类</span>
                    <select
                      v-model="doc.categoryId"
                      class="w-full rounded-lg border border-border px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    >
                      <option value="">未设置</option>
                      <option v-for="category in referenceCategories" :key="category.id" :value="category.id">{{ category.name }}</option>
                    </select>
                  </label>

                  <div class="flex items-end md:col-span-2 xl:col-span-1">
                    <button
                      class="inline-flex w-full items-center justify-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm text-white transition-colors hover:bg-primary-light disabled:cursor-not-allowed disabled:opacity-50"
                      :disabled="savingDocumentId === doc.id"
                      @click="saveDocument(doc)"
                    >
                      <Save class="h-4 w-4" />
                      {{ savingDocumentId === doc.id ? '保存中' : '保存' }}
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </section>
        </div>
      </template>
    </section>
  </div>
</template>
