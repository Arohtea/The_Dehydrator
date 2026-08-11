<script setup>
/**
 * 参考资料库管理页。
 *
 * 资料库、文件夹、分类和参考文件共用 Store 中的状态；切换资料库时使用递增令牌
 * 丢弃较早请求的返回值，防止用户快速点击不同资料库后，慢请求把旧资料覆盖到当前视图。
 */
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import {
  BookOpen,
  CircleCheck,
  ChevronDown,
  Folder,
  FolderPlus,
  LoaderCircle,
  Plus,
  Search,
  Tags,
  Trash2,
  Upload,
  FileText,
  Save,
  PencilLine,
  ShieldCheck,
  X,
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
const documentSearch = ref('')
const renamingFolderId = ref('')
const renamingCategoryId = ref('')
const deletingFolderId = ref('')
const deletingCategoryId = ref('')
let librarySelectionToken = 0

// 右侧编辑区始终以当前选中的资料库为准，列表删除后会自然退回到下一个可用资料库。
const activeLibrary = computed(() =>
  referenceLibraries.value.find(item => item.id === selectedLibraryId.value) || null
)

const documentStats = computed(() => ({
  total: referenceDocuments.value.length,
  ready: referenceDocuments.value.filter(document => document.aiDocId).length,
  unorganized: referenceDocuments.value.filter(document => !document.folderId || !document.categoryId).length,
}))

const filteredReferenceDocuments = computed(() => {
  const query = documentSearch.value.trim().toLowerCase()
  if (!query) return referenceDocuments.value

  return referenceDocuments.value.filter(document =>
    [document.displayName, document.filename]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(query))
  )
})

/**
 * 将后端时间统一成适合列表阅读的短日期格式。
 *
 * @param {string|number|Date|null} value 后端返回的时间值。
 * @returns {string} 本地化后的日期文本；无法解析时返回占位文本。
 */
function formatDateTime(value) {
  if (!value) return '未记录时间'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '未记录时间'
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * 从原文件名提取短文件类型，帮助用户快速扫描列表。
 *
 * @param {string} filename 原文件名。
 * @returns {string} 大写文件类型或 FILE 占位文本。
 */
function getFileType(filename) {
  const name = String(filename || '')
  const extension = name.split('.').pop()
  return extension && extension !== name ? extension.slice(0, 5).toUpperCase() : 'FILE'
}

// 先加载资料库列表，再默认进入第一项；没有资料库时保留空状态供用户新建。
onMounted(async () => {
  const libraries = await store.fetchReferenceLibraries()
  if (libraries.length) {
    await selectLibrary(libraries[0].id)
  }
})

/**
 * 切换当前资料库并并行加载其文档、文件夹和分类。
 *
 * 每次切换都会生成新的选择令牌。只有最后一次选择仍然有效时，异步请求结果才会
 * 提交到共享状态；这保证快速切换时不会把旧资料库的内容显示在新资料库下。
 *
 * @param {string|number} libraryId 要查看的资料库 ID。
 * @returns {Promise<void>} 资料库三类资源加载完成后返回。
 */
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

/**
 * 创建资料库并自动切换到新建资料库。
 *
 * @returns {Promise<void>} 创建和首次资源加载完成后返回。
 */
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

/**
 * 删除普通资料库，并在删除当前项后选择剩余列表中的第一项。
 *
 * 系统资料库承载分析上传文档的自动归档，前端在请求前直接阻止删除；普通资料库仍
 * 需要用户确认，具体的“只能删除空资料库”约束由服务端最终判断。
 *
 * @param {Object} library 待删除的资料库对象。
 * @returns {Promise<void>} 删除及选中项调整完成后返回。
 */
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

/**
 * 向当前资料库上传一个参考文件并刷新其文档列表。
 *
 * 选择器值会在读取文件后立即清空，因此用户可以再次选择同名文件触发 change；
 * 上传锁则保证同一资料库不会并发提交两个文件。
 *
 * @param {Event} event 文件输入框变更事件。
 * @returns {Promise<void>} 上传和列表刷新完成后返回。
 */
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

/**
 * 在当前资料库创建文件夹，并清空已提交的名称。
 *
 * @returns {Promise<void>} 创建请求完成后返回。
 */
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

/**
 * 在当前资料库创建分类，并清空已提交的名称。
 *
 * @returns {Promise<void>} 创建请求完成后返回。
 */
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

/**
 * 保存文件夹的重命名结果；失败时重新读取列表恢复服务端值。
 *
 * @param {Object} folder 正在编辑的文件夹对象。
 * @returns {Promise<void>} 保存请求完成后返回。
 */
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

/**
 * 保存分类的重命名结果；失败时重新读取列表恢复服务端值。
 *
 * @param {Object} category 正在编辑的分类对象。
 * @returns {Promise<void>} 保存请求完成后返回。
 */
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

/**
 * 删除文件夹并刷新文档列表，以反映服务端对关联资料的处理结果。
 *
 * @param {Object} folder 待删除的文件夹对象。
 * @returns {Promise<void>} 删除请求和资料列表刷新完成后返回。
 */
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

/**
 * 删除分类并刷新文档列表，以反映服务端对关联资料的处理结果。
 *
 * @param {Object} category 待删除的分类对象。
 * @returns {Promise<void>} 删除请求和资料列表刷新完成后返回。
 */
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

/**
 * 保存参考文档的展示名称、文件夹和分类归属。
 *
 * 单个文档使用 ID 锁避免重复提交；失败时重新读取当前资料库文档，撤销输入框中
 * 尚未被服务端接受的本地编辑。
 *
 * @param {Object} doc 正在编辑的参考文档对象。
 * @returns {Promise<void>} 保存请求完成后返回。
 */
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

/**
 * 删除普通参考文件并刷新当前资料库文档列表。
 *
 * 自动归档的源文档不会显示删除按钮；其生命周期由分析文档删除流程负责，避免在
 * 资料库页面绕过业务关联约束。
 *
 * @param {Object} doc 待删除的参考文档对象。
 * @returns {Promise<void>} 删除请求和列表刷新完成后返回。
 */
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
  <div class="library-page">
    <header class="library-hero">
      <div>
        <div class="library-kicker">
          <BookOpen class="h-4 w-4" />
          资料检索工作台
        </div>
        <h1 class="font-heading text-4xl font-semibold leading-tight text-text sm:text-5xl">参考资料库</h1>
        <p class="mt-3 max-w-2xl text-sm leading-6 text-text-muted sm:text-base">
          把论文、规范和研究材料放在一起，分析时可以直接作为可信的参考来源。
        </p>
      </div>
      <div class="library-hero-meta">
        <span>{{ referenceLibraries.length }} 个资料库</span>
        <span>用于分析时的参考检索</span>
      </div>
    </header>

    <div class="library-layout">
      <aside class="library-sidebar">
        <div class="library-sidebar-heading">
          <div>
            <p class="text-sm font-semibold text-text">我的资料库</p>
            <p class="mt-1 text-xs text-text-muted">{{ referenceLibraries.length }} 个来源</p>
          </div>
          <div class="library-sidebar-icon"><BookOpen class="h-5 w-5" /></div>
        </div>

        <form class="library-create-form" @submit.prevent="createLibrary">
          <label class="sr-only" for="new-library-name">新建资料库</label>
          <input
            id="new-library-name"
            v-model="createLibraryName"
            type="text"
            placeholder="新建资料库"
            autocomplete="off"
            class="library-input min-w-0 flex-1"
          />
          <button
            type="submit"
            :disabled="creatingLibrary"
            class="library-icon-button library-icon-button-primary"
            title="创建资料库"
            aria-label="创建资料库"
          >
            <Plus class="h-4 w-4" />
          </button>
        </form>

        <p class="library-sidebar-label">选择一个资料库开始整理</p>
        <div class="library-nav-list">
          <div v-for="library in referenceLibraries" :key="library.id" class="library-nav-row">
            <button
              type="button"
              class="library-nav-button"
              :class="selectedLibraryId === library.id ? 'library-nav-button-active' : ''"
              :aria-current="selectedLibraryId === library.id ? 'page' : undefined"
              @click="selectLibrary(library.id)"
            >
              <span class="library-nav-main">
                <span class="library-nav-symbol" :class="library.systemKey ? 'library-nav-symbol-system' : ''">
                  <ShieldCheck v-if="library.systemKey" class="h-4 w-4" />
                  <Folder v-else class="h-4 w-4" />
                </span>
                <span class="min-w-0">
                  <span class="library-nav-name">{{ library.name }}</span>
                  <span class="library-nav-date">{{ formatDateTime(library.createdAt) }}</span>
                </span>
              </span>
              <span class="library-nav-type">{{ library.systemKey ? '系统' : '自建' }}</span>
            </button>
            <button
              v-if="!library.systemKey"
              type="button"
              class="library-icon-button library-icon-button-danger"
              :disabled="deletingLibraryId === library.id"
              title="删除资料库"
              :aria-label="`删除资料库 ${library.name}`"
              @click="removeLibrary(library)"
            >
              <Trash2 class="h-4 w-4" />
            </button>
          </div>

          <div v-if="referenceLibraries.length === 0" class="library-empty-sidebar">
            <FolderPlus class="mx-auto h-5 w-5 text-text-muted" />
            <p class="mt-2 text-sm text-text-muted">还没有资料库</p>
            <p class="mt-1 text-xs text-text-muted">从上方输入名称创建</p>
          </div>
        </div>

        <div class="library-sidebar-footnote">
          <ShieldCheck class="h-4 w-4 shrink-0 text-primary" />
          <p>系统资料库由分析流程自动维护，不能手动删除。</p>
        </div>
      </aside>

      <section class="library-workspace">
        <div v-if="!activeLibrary" class="library-empty-workspace">
          <div class="library-empty-mark"><BookOpen class="h-6 w-6" /></div>
          <h2 class="mt-4 font-heading text-2xl font-semibold text-text">先选择一个资料库</h2>
          <p class="mt-2 max-w-sm text-sm leading-6 text-text-muted">选择左侧资料库，或新建一个资料库来上传和整理参考文件。</p>
        </div>

        <template v-else>
          <header class="workspace-header">
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <span class="workspace-eyebrow">当前资料库</span>
                <span v-if="activeLibrary.systemKey" class="workspace-system-badge">
                  <ShieldCheck class="h-3.5 w-3.5" />
                  自动归档
                </span>
              </div>
              <h2 class="mt-2 truncate font-heading text-3xl font-semibold leading-tight text-text">{{ activeLibrary.name }}</h2>
              <p class="mt-2 max-w-2xl text-sm leading-6 text-text-muted">
                {{ activeLibrary.systemKey ? '分析上传的论文会自动归档到这里，完成向量化后可参与参考检索。' : '上传参考文件，再用文件夹和分类建立清晰的资料结构。' }}
              </p>
            </div>

            <label class="upload-action" :class="uploading ? 'upload-action-disabled' : ''">
              <Upload class="h-4 w-4" />
              <span>{{ uploading ? '上传中...' : '上传资料' }}</span>
              <input type="file" accept=".pdf,.docx,.txt" class="hidden" :disabled="uploading" @change="uploadFiles" />
            </label>
          </header>

          <div v-if="loadingLibraryData" class="library-loading-state" aria-live="polite">
            <div v-for="i in 4" :key="i" class="library-skeleton" :class="i === 1 ? 'library-skeleton-wide' : ''"></div>
          </div>

          <div v-else>
            <div class="library-stats" aria-label="资料库统计">
              <div class="library-stat">
                <span class="library-stat-label">当前文件</span>
                <strong>{{ documentStats.total }}</strong>
                <span>份</span>
              </div>
              <div class="library-stat">
                <span class="library-stat-label">已完成检索</span>
                <strong>{{ documentStats.ready }}</strong>
                <span>份</span>
              </div>
              <div class="library-stat">
                <span class="library-stat-label">待整理</span>
                <strong>{{ documentStats.unorganized }}</strong>
                <span>份</span>
              </div>
            </div>

            <details class="organize-panel">
              <summary class="organize-summary">
                <span class="organize-summary-main">
                  <span class="organize-summary-icon"><FolderPlus class="h-4 w-4" /></span>
                  <span>
                    <strong>整理方式</strong>
                    <span>维护文件夹和分类，让资料更容易被找到</span>
                  </span>
                </span>
                <span class="organize-summary-meta">
                  {{ referenceFolders.length }} 个文件夹 · {{ referenceCategories.length }} 个分类
                  <ChevronDown class="h-4 w-4" />
                </span>
              </summary>

              <div class="organize-grid">
                <section class="organize-section">
                  <div class="organize-section-heading">
                    <div class="organize-section-title">
                      <span class="organize-icon organize-icon-blue"><Folder class="h-4 w-4" /></span>
                      <div>
                        <h3>文件夹</h3>
                        <p>按项目或主题收纳文件</p>
                      </div>
                    </div>
                    <span class="organize-count">{{ referenceFolders.length }}</span>
                  </div>
                  <form class="organize-create-form" @submit.prevent="createFolder">
                    <label class="sr-only" for="new-folder-name">新建文件夹</label>
                    <input
                      id="new-folder-name"
                      v-model="createFolderName"
                      type="text"
                      placeholder="输入文件夹名称"
                      class="library-input min-w-0 flex-1"
                    />
                    <button type="submit" :disabled="creatingFolder" class="text-button text-button-blue">
                      {{ creatingFolder ? '创建中' : '新增' }}
                    </button>
                  </form>
                  <div class="organize-list">
                    <div v-for="folder in referenceFolders" :key="folder.id" class="organize-row">
                      <input
                        v-model="folder.name"
                        type="text"
                        class="organize-name-input"
                        :aria-label="`文件夹名称 ${folder.name}`"
                        @keyup.enter="renameFolder(folder)"
                      />
                      <button
                        type="button"
                        class="library-icon-button library-icon-button-muted"
                        :disabled="renamingFolderId === folder.id"
                        title="保存文件夹名称"
                        :aria-label="`保存文件夹 ${folder.name}`"
                        @click="renameFolder(folder)"
                      >
                        <Save class="h-4 w-4" />
                      </button>
                      <button
                        type="button"
                        class="library-icon-button library-icon-button-danger"
                        :disabled="deletingFolderId === folder.id"
                        title="删除文件夹"
                        :aria-label="`删除文件夹 ${folder.name}`"
                        @click="removeFolder(folder)"
                      >
                        <Trash2 class="h-4 w-4" />
                      </button>
                    </div>
                    <p v-if="referenceFolders.length === 0" class="organize-empty">还没有文件夹</p>
                  </div>
                </section>

                <section class="organize-section">
                  <div class="organize-section-heading">
                    <div class="organize-section-title">
                      <span class="organize-icon organize-icon-amber"><Tags class="h-4 w-4" /></span>
                      <div>
                        <h3>分类</h3>
                        <p>给文件添加可复用的主题标签</p>
                      </div>
                    </div>
                    <span class="organize-count">{{ referenceCategories.length }}</span>
                  </div>
                  <form class="organize-create-form" @submit.prevent="createCategory">
                    <label class="sr-only" for="new-category-name">新建分类</label>
                    <input
                      id="new-category-name"
                      v-model="createCategoryName"
                      type="text"
                      placeholder="输入分类名称"
                      class="library-input min-w-0 flex-1"
                    />
                    <button type="submit" :disabled="creatingCategory" class="text-button text-button-amber">
                      {{ creatingCategory ? '创建中' : '新增' }}
                    </button>
                  </form>
                  <div class="organize-list">
                    <div v-for="category in referenceCategories" :key="category.id" class="organize-row">
                      <input
                        v-model="category.name"
                        type="text"
                        class="organize-name-input"
                        :aria-label="`分类名称 ${category.name}`"
                        @keyup.enter="renameCategory(category)"
                      />
                      <button
                        type="button"
                        class="library-icon-button library-icon-button-muted"
                        :disabled="renamingCategoryId === category.id"
                        title="保存分类名称"
                        :aria-label="`保存分类 ${category.name}`"
                        @click="renameCategory(category)"
                      >
                        <Save class="h-4 w-4" />
                      </button>
                      <button
                        type="button"
                        class="library-icon-button library-icon-button-danger"
                        :disabled="deletingCategoryId === category.id"
                        title="删除分类"
                        :aria-label="`删除分类 ${category.name}`"
                        @click="removeCategory(category)"
                      >
                        <Trash2 class="h-4 w-4" />
                      </button>
                    </div>
                    <p v-if="referenceCategories.length === 0" class="organize-empty">还没有分类</p>
                  </div>
                </section>
              </div>
            </details>

            <section class="documents-section">
              <div class="documents-toolbar">
                <div>
                  <div class="flex items-center gap-2">
                    <h3 class="font-heading text-2xl font-semibold text-text">资料文件</h3>
                    <span class="documents-count">{{ documentStats.total }}</span>
                  </div>
                  <p class="mt-1 text-sm text-text-muted">
                    {{ documentSearch ? `找到 ${filteredReferenceDocuments.length} 个匹配文件` : '在这里查看、命名和整理当前资料库中的文件' }}
                  </p>
                </div>
                <div class="search-field">
                  <Search class="h-4 w-4 shrink-0 text-text-muted" />
                  <label class="sr-only" for="document-search">搜索资料文件</label>
                  <input
                    id="document-search"
                    v-model="documentSearch"
                    type="search"
                    placeholder="搜索文件名"
                    class="min-w-0 flex-1 bg-transparent text-sm text-text outline-none placeholder:text-text-muted"
                  />
                  <button
                    v-if="documentSearch"
                    type="button"
                    class="search-clear"
                    title="清空搜索"
                    aria-label="清空搜索"
                    @click="documentSearch = ''"
                  >
                    <X class="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>

              <div v-if="referenceDocuments.length === 0" class="document-empty-state">
                <div class="document-empty-icon"><FileText class="h-6 w-6" /></div>
                <h4>这个资料库还没有文件</h4>
                <p>上传 PDF、DOCX 或 TXT 文件，之后就能在分析时引用它们。</p>
                <label class="empty-upload-action">
                  <Upload class="h-4 w-4" />
                  上传第一份资料
                  <input type="file" accept=".pdf,.docx,.txt" class="hidden" :disabled="uploading" @change="uploadFiles" />
                </label>
              </div>

              <div v-else-if="filteredReferenceDocuments.length === 0" class="document-empty-state document-empty-state-compact">
                <div class="document-empty-icon"><Search class="h-6 w-6" /></div>
                <h4>没有匹配的文件</h4>
                <p>试试文件的展示名称或原始文件名。</p>
                <button type="button" class="text-button text-button-blue" @click="documentSearch = ''">清空搜索</button>
              </div>

              <div v-else class="documents-list">
                <article v-for="doc in filteredReferenceDocuments" :key="doc.id" class="document-row">
                  <div class="document-row-header">
                    <div class="document-heading">
                      <div class="file-type-mark">
                        <FileText class="h-5 w-5" />
                        <span>{{ getFileType(doc.filename) }}</span>
                      </div>
                      <div class="min-w-0">
                        <h4 class="document-name">{{ doc.displayName || doc.filename }}</h4>
                        <p class="document-original-name">原文件：{{ doc.filename }}</p>
                      </div>
                    </div>
                    <div class="document-row-actions">
                      <span class="document-status" :class="doc.aiDocId ? 'document-status-ready' : 'document-status-pending'">
                        <CircleCheck v-if="doc.aiDocId" class="h-3.5 w-3.5" />
                        <LoaderCircle v-else class="h-3.5 w-3.5 animate-spin" />
                        {{ doc.aiDocId ? '已完成检索' : '正在处理' }}
                      </span>
                      <span v-if="doc.sourceDocumentId" class="document-source-badge">
                        <ShieldCheck class="h-3.5 w-3.5" />
                        自动归档
                      </span>
                      <button
                        v-if="!doc.sourceDocumentId"
                        type="button"
                        class="library-icon-button library-icon-button-danger"
                        :disabled="deletingDocumentId === doc.id"
                        title="删除资料文件"
                        :aria-label="`删除资料文件 ${doc.displayName || doc.filename}`"
                        @click="removeDocument(doc)"
                      >
                        <Trash2 class="h-4 w-4" />
                      </button>
                    </div>
                  </div>

                  <div class="document-meta-line">
                    <span>添加于 {{ formatDateTime(doc.createdAt) }}</span>
                    <span v-if="doc.sourceDocumentId">由分析流程自动加入，名称和归属仍可调整</span>
                  </div>

                  <div class="document-editor-grid">
                    <label class="editor-field editor-field-wide">
                      <span>展示名称</span>
                      <div class="editor-input-wrap">
                        <PencilLine class="h-4 w-4 shrink-0 text-text-muted" />
                        <input v-model="doc.displayName" type="text" class="editor-input" />
                      </div>
                    </label>

                    <label class="editor-field">
                      <span>文件夹</span>
                      <select v-model="doc.folderId" class="editor-input editor-select">
                        <option value="">未设置</option>
                        <option v-for="folder in referenceFolders" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
                      </select>
                    </label>

                    <label class="editor-field">
                      <span>分类</span>
                      <select v-model="doc.categoryId" class="editor-input editor-select">
                        <option value="">未设置</option>
                        <option v-for="category in referenceCategories" :key="category.id" :value="category.id">{{ category.name }}</option>
                      </select>
                    </label>

                    <div class="document-editor-action">
                      <button
                        type="button"
                        class="save-document-button"
                        :disabled="savingDocumentId === doc.id"
                        @click="saveDocument(doc)"
                      >
                        <Save class="h-4 w-4" />
                        {{ savingDocumentId === doc.id ? '保存中' : '保存设置' }}
                      </button>
                    </div>
                  </div>
                </article>
              </div>
            </section>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.library-page {
  --library-ink: #16213a;
  --library-muted: #66758f;
  --library-line: #e5eaf2;
  --library-panel: #f7f9fc;
  --library-blue: #1d4ed8;
  --library-blue-soft: #eef4ff;
  --library-amber: #b96b12;
  --library-amber-soft: #fff6e8;
  --library-green: #18794e;
  padding-bottom: 2rem;
}

.library-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 2rem;
  padding: 0.5rem 0 1.5rem;
}

.library-kicker {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
  color: var(--library-blue);
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.library-hero-meta {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  border-left: 1px solid var(--library-line);
  padding-left: 1rem;
  color: var(--library-muted);
  font-size: 0.75rem;
  line-height: 1.5;
  text-align: right;
}

.library-layout {
  display: grid;
  grid-template-columns: minmax(250px, 300px) minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--library-line);
  border-radius: 1.25rem;
  background: #fff;
  box-shadow: 0 18px 50px rgba(22, 33, 58, 0.07);
}

.library-sidebar {
  display: flex;
  min-height: 42rem;
  flex-direction: column;
  padding: 1.25rem;
  border-right: 1px solid var(--library-line);
  background: #fbfcfe;
}

.library-sidebar-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.25rem 0.25rem 1rem;
}

.library-sidebar-icon,
.library-empty-mark,
.document-empty-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 0.8rem;
  background: var(--library-blue-soft);
  color: var(--library-blue);
}

.library-sidebar-icon {
  width: 2.25rem;
  height: 2.25rem;
}

.library-create-form,
.organize-create-form {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.library-input {
  height: 2.5rem;
  border: 1px solid var(--library-line);
  border-radius: 0.7rem;
  background: #fff;
  padding: 0 0.75rem;
  color: var(--library-ink);
  font-size: 0.875rem;
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.library-input::placeholder,
.organize-name-input::placeholder {
  color: #94a3b8;
}

.library-input:focus,
.organize-name-input:focus,
.editor-input:focus,
.editor-input-wrap:focus-within,
.search-field:focus-within {
  border-color: #8aa9e9;
  box-shadow: 0 0 0 3px rgba(29, 78, 216, 0.1);
}

.library-icon-button {
  display: inline-flex;
  width: 2.25rem;
  height: 2.25rem;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 0.65rem;
  cursor: pointer;
  transition: background-color 160ms ease, border-color 160ms ease, color 160ms ease;
}

.library-icon-button:disabled,
.text-button:disabled,
.save-document-button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.library-nav-button:focus-visible,
.library-icon-button:focus-visible,
.text-button:focus-visible,
.upload-action:focus-within,
.empty-upload-action:focus-within,
.save-document-button:focus-visible,
.search-clear:focus-visible {
  outline: 3px solid rgba(29, 78, 216, 0.24);
  outline-offset: 2px;
}

.library-icon-button-primary {
  border-color: var(--library-blue);
  background: var(--library-blue);
  color: #fff;
}

.library-icon-button-primary:hover:not(:disabled) {
  border-color: #1741b5;
  background: #1741b5;
}

.library-icon-button-muted {
  color: var(--library-blue);
}

.library-icon-button-muted:hover:not(:disabled) {
  background: var(--library-blue-soft);
}

.library-icon-button-danger {
  color: #c2413f;
}

.library-icon-button-danger:hover:not(:disabled) {
  background: #fff1f1;
  color: #a52f2c;
}

.library-sidebar-label {
  margin: 1.5rem 0 0.6rem;
  color: var(--library-muted);
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.library-nav-list {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.library-nav-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: stretch;
  gap: 0.25rem;
}

.library-nav-button {
  display: flex;
  min-width: 0;
  min-height: 4.25rem;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem;
  border: 1px solid transparent;
  border-radius: 0.8rem;
  background: transparent;
  padding: 0.7rem 0.75rem;
  color: var(--library-ink);
  text-align: left;
  cursor: pointer;
  transition: background-color 160ms ease, border-color 160ms ease, box-shadow 160ms ease;
}

.library-nav-button:hover {
  border-color: #d5e0f6;
  background: #f5f8fd;
}

.library-nav-button-active {
  border-color: #d3def5;
  background: var(--library-blue-soft);
  box-shadow: inset 3px 0 0 var(--library-blue);
}

.library-nav-main {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 0.7rem;
}

.library-nav-symbol {
  display: inline-flex;
  width: 2rem;
  height: 2rem;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 0.6rem;
  background: #eef2f7;
  color: #64748b;
}

.library-nav-symbol-system {
  background: #eaf5ef;
  color: var(--library-green);
}

.library-nav-name,
.library-nav-date {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.library-nav-name {
  font-size: 0.875rem;
  font-weight: 650;
}

.library-nav-date {
  margin-top: 0.2rem;
  color: var(--library-muted);
  font-size: 0.68rem;
}

.library-nav-type,
.documents-count,
.organize-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: #eef2f7;
  color: var(--library-muted);
  font-size: 0.68rem;
  font-weight: 700;
  white-space: nowrap;
}

.library-nav-type {
  padding: 0.22rem 0.45rem;
}

.library-empty-sidebar {
  border: 1px dashed #cbd5e1;
  border-radius: 0.8rem;
  padding: 2rem 1rem;
  text-align: center;
}

.library-sidebar-footnote {
  display: flex;
  gap: 0.55rem;
  align-items: flex-start;
  margin-top: auto;
  border-top: 1px solid var(--library-line);
  padding: 1rem 0.25rem 0;
  color: var(--library-muted);
  font-size: 0.72rem;
  line-height: 1.55;
}

.library-workspace {
  min-width: 0;
  padding: clamp(1.25rem, 3vw, 2.25rem);
}

.library-empty-workspace {
  display: flex;
  min-height: 36rem;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.library-empty-mark {
  width: 3.5rem;
  height: 3.5rem;
  border-radius: 1rem;
}

.workspace-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 2rem;
  border-bottom: 1px solid var(--library-line);
  padding-bottom: 1.5rem;
}

.workspace-eyebrow {
  color: var(--library-muted);
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.07em;
  text-transform: uppercase;
}

.workspace-system-badge,
.document-source-badge,
.document-status {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  border-radius: 999px;
  font-size: 0.7rem;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
}

.workspace-system-badge {
  background: #eaf5ef;
  padding: 0.35rem 0.55rem;
  color: var(--library-green);
}

.upload-action,
.empty-upload-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  border-radius: 0.75rem;
  background: var(--library-amber);
  color: #fff;
  font-size: 0.875rem;
  font-weight: 700;
  cursor: pointer;
  transition: background-color 160ms ease, transform 160ms ease;
}

.upload-action {
  min-height: 2.75rem;
  flex: 0 0 auto;
  padding: 0 1rem;
}

.upload-action:hover,
.empty-upload-action:hover {
  background: #a85f0e;
  transform: translateY(-1px);
}

.upload-action-disabled {
  cursor: wait;
  opacity: 0.7;
  transform: none;
}

.library-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-bottom: 1px solid var(--library-line);
}

.library-stat {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: baseline;
  gap: 0.35rem;
  min-height: 4.5rem;
  border-right: 1px solid var(--library-line);
  padding: 1rem 1.25rem;
  color: var(--library-muted);
  font-size: 0.72rem;
}

.library-stat:last-child {
  border-right: 0;
}

.library-stat strong {
  color: var(--library-ink);
  font-size: 1.6rem;
  font-weight: 700;
  line-height: 1;
}

.library-stat-label {
  color: var(--library-muted);
  font-weight: 650;
}

.organize-panel {
  border-bottom: 1px solid var(--library-line);
}

.organize-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem 0;
  color: var(--library-ink);
  cursor: pointer;
  list-style: none;
}

.organize-summary::-webkit-details-marker {
  display: none;
}

.organize-summary-main,
.organize-summary-meta,
.organize-section-title {
  display: inline-flex;
  align-items: center;
}

.organize-summary-main {
  min-width: 0;
  gap: 0.65rem;
}

.organize-summary-main > span:last-child {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 0.15rem;
}

.organize-summary-main strong {
  font-size: 0.875rem;
}

.organize-summary-main span:last-child span,
.organize-summary-meta {
  color: var(--library-muted);
  font-size: 0.72rem;
}

.organize-summary-icon,
.organize-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 0.6rem;
}

.organize-summary-icon {
  width: 2rem;
  height: 2rem;
  background: #f3f0ff;
  color: #6d55b8;
}

.organize-summary-meta {
  gap: 0.5rem;
  flex: 0 0 auto;
}

.organize-summary-meta svg {
  transition: transform 160ms ease;
}

.organize-panel[open] .organize-summary-meta svg {
  transform: rotate(180deg);
}

.organize-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2rem;
  border-top: 1px solid var(--library-line);
  padding: 1.25rem 0 1.5rem;
}

.organize-section + .organize-section {
  border-left: 1px solid var(--library-line);
  padding-left: 2rem;
}

.organize-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.organize-section-title {
  gap: 0.65rem;
}

.organize-icon {
  width: 2rem;
  height: 2rem;
}

.organize-icon-blue {
  background: var(--library-blue-soft);
  color: var(--library-blue);
}

.organize-icon-amber {
  background: var(--library-amber-soft);
  color: var(--library-amber);
}

.organize-section-title h3 {
  color: var(--library-ink);
  font-size: 0.875rem;
  font-weight: 700;
}

.organize-section-title p {
  margin-top: 0.15rem;
  color: var(--library-muted);
  font-size: 0.7rem;
}

.organize-count {
  min-width: 1.5rem;
  height: 1.5rem;
}

.organize-create-form {
  margin-top: 0.9rem;
}

.text-button {
  height: 2.5rem;
  padding: 0 0.8rem;
  border-radius: 0.65rem;
  font-size: 0.75rem;
  font-weight: 700;
  cursor: pointer;
  transition: background-color 160ms ease, color 160ms ease;
}

.text-button-blue {
  color: var(--library-blue);
}

.text-button-blue:hover:not(:disabled) {
  background: var(--library-blue-soft);
}

.text-button-amber {
  color: var(--library-amber);
}

.text-button-amber:hover:not(:disabled) {
  background: var(--library-amber-soft);
}

.organize-list {
  margin-top: 0.8rem;
}

.organize-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 0.35rem;
  min-height: 3rem;
  border-bottom: 1px solid #eef1f5;
}

.organize-name-input {
  min-width: 0;
  height: 2.2rem;
  border: 1px solid transparent;
  border-radius: 0.55rem;
  background: transparent;
  padding: 0 0.55rem;
  color: var(--library-ink);
  font-size: 0.8rem;
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.organize-empty {
  padding: 0.75rem 0;
  color: var(--library-muted);
  font-size: 0.75rem;
}

.documents-section {
  padding-top: 1.75rem;
}

.documents-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1.5rem;
  padding-bottom: 1rem;
}

.documents-count {
  min-width: 1.65rem;
  height: 1.65rem;
  background: var(--library-blue-soft);
  color: var(--library-blue);
}

.search-field {
  display: flex;
  width: min(100%, 18rem);
  min-height: 2.65rem;
  align-items: center;
  gap: 0.5rem;
  border: 1px solid var(--library-line);
  border-radius: 0.75rem;
  background: #fff;
  padding: 0 0.7rem;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.search-clear {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  flex: 0 0 auto;
  border-radius: 0.4rem;
  color: var(--library-muted);
  cursor: pointer;
}

.search-clear:hover {
  background: #eef2f7;
  color: var(--library-ink);
}

.document-empty-state {
  display: flex;
  min-height: 18rem;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin-top: 0.25rem;
  border: 1px dashed #cbd5e1;
  border-radius: 1rem;
  background: var(--library-panel);
  padding: 2.5rem 1.25rem;
  text-align: center;
}

.document-empty-state-compact {
  min-height: 14rem;
}

.document-empty-icon {
  width: 3rem;
  height: 3rem;
}

.document-empty-state h4 {
  margin-top: 1rem;
  color: var(--library-ink);
  font-size: 0.95rem;
  font-weight: 700;
}

.document-empty-state p {
  max-width: 28rem;
  margin-top: 0.4rem;
  color: var(--library-muted);
  font-size: 0.8rem;
  line-height: 1.6;
}

.empty-upload-action {
  min-height: 2.5rem;
  margin-top: 1.25rem;
  padding: 0 0.9rem;
  font-size: 0.8rem;
}

.documents-list {
  display: grid;
  gap: 0.8rem;
}

.document-row {
  border: 1px solid var(--library-line);
  border-radius: 0.95rem;
  background: #fff;
  padding: 1rem;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.document-row:hover {
  border-color: #c8d6ef;
  box-shadow: 0 8px 22px rgba(22, 33, 58, 0.06);
}

.document-row-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.document-heading {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 0.8rem;
}

.file-type-mark {
  display: flex;
  width: 3rem;
  height: 3.25rem;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.2rem;
  border: 1px solid #d9e4fb;
  border-radius: 0.75rem;
  background: #f2f6ff;
  color: var(--library-blue);
}

.file-type-mark span {
  font-size: 0.55rem;
  font-weight: 800;
  letter-spacing: 0.05em;
}

.document-name {
  overflow-wrap: anywhere;
  color: var(--library-ink);
  font-size: 0.95rem;
  font-weight: 700;
  line-height: 1.45;
}

.document-original-name {
  overflow-wrap: anywhere;
  margin-top: 0.28rem;
  color: var(--library-muted);
  font-size: 0.7rem;
  line-height: 1.45;
}

.document-row-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 0.45rem;
}

.document-status {
  padding: 0.42rem 0.6rem;
}

.document-status-ready {
  background: #eaf5ef;
  color: var(--library-green);
}

.document-status-pending {
  background: var(--library-amber-soft);
  color: var(--library-amber);
}

.document-source-badge {
  background: #f2f0ff;
  padding: 0.42rem 0.6rem;
  color: #6d55b8;
}

.document-meta-line {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem 1rem;
  margin: 0.9rem 0 0;
  padding: 0.75rem 0 0 3.8rem;
  border-top: 1px solid #eef1f5;
  color: var(--library-muted);
  font-size: 0.7rem;
  line-height: 1.5;
}

.document-editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(140px, 0.7fr) minmax(140px, 0.7fr) auto;
  align-items: end;
  gap: 0.75rem;
  margin-top: 0.75rem;
}

.editor-field {
  display: block;
  min-width: 0;
}

.editor-field > span {
  display: block;
  margin-bottom: 0.35rem;
  color: var(--library-muted);
  font-size: 0.68rem;
  font-weight: 700;
}

.editor-input-wrap {
  display: flex;
  min-width: 0;
  height: 2.5rem;
  align-items: center;
  gap: 0.45rem;
  border: 1px solid var(--library-line);
  border-radius: 0.7rem;
  background: #fff;
  padding: 0 0.65rem;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.editor-input {
  width: 100%;
  min-width: 0;
  height: 2.5rem;
  border: 1px solid var(--library-line);
  border-radius: 0.7rem;
  background: #fff;
  padding: 0 0.7rem;
  color: var(--library-ink);
  font-size: 0.8rem;
  outline: none;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.editor-input-wrap .editor-input {
  height: 2rem;
  border: 0;
  padding: 0;
  box-shadow: none;
}

.editor-select {
  cursor: pointer;
}

.document-editor-action {
  display: flex;
  min-width: 6.5rem;
}

.save-document-button {
  display: inline-flex;
  width: 100%;
  height: 2.5rem;
  align-items: center;
  justify-content: center;
  gap: 0.45rem;
  border-radius: 0.7rem;
  background: var(--library-blue);
  color: #fff;
  font-size: 0.8rem;
  font-weight: 700;
  cursor: pointer;
  transition: background-color 160ms ease;
}

.save-document-button:hover:not(:disabled) {
  background: #1741b5;
}

.library-loading-state {
  display: grid;
  gap: 0.75rem;
  padding: 1.5rem 0;
}

.library-skeleton {
  height: 4.25rem;
  border-radius: 0.8rem;
  background: linear-gradient(90deg, #f4f6fa 0%, #e9eef6 50%, #f4f6fa 100%);
  background-size: 200% 100%;
  animation: library-loading 1.4s ease-in-out infinite;
}

.library-skeleton-wide {
  height: 6rem;
}

@keyframes library-loading {
  from {
    background-position: 100% 0;
  }
  to {
    background-position: -100% 0;
  }
}

@media (max-width: 1100px) {
  .library-layout {
    grid-template-columns: minmax(220px, 260px) minmax(0, 1fr);
  }

  .document-editor-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .editor-field-wide,
  .document-editor-action {
    grid-column: 1 / -1;
  }
}

@media (max-width: 860px) {
  .library-hero {
    align-items: flex-start;
    flex-direction: column;
    gap: 1rem;
  }

  .library-hero-meta {
    align-items: flex-start;
    border-left: 0;
    border-top: 1px solid var(--library-line);
    padding: 0.75rem 0 0;
    text-align: left;
  }

  .library-layout {
    grid-template-columns: 1fr;
  }

  .library-sidebar {
    min-height: auto;
    border-right: 0;
    border-bottom: 1px solid var(--library-line);
  }

  .library-nav-list {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  }

  .library-sidebar-footnote {
    margin-top: 1rem;
  }

  .organize-grid {
    gap: 1.25rem;
  }

  .organize-section + .organize-section {
    padding-left: 1.25rem;
  }
}

@media (max-width: 600px) {
  .library-workspace,
  .library-sidebar {
    padding: 1rem;
  }

  .workspace-header,
  .documents-toolbar,
  .document-row-header {
    display: block;
  }

  .upload-action,
  .search-field {
    width: 100%;
    margin-top: 1rem;
  }

  .library-stats {
    grid-template-columns: 1fr;
  }

  .library-stat {
    min-height: 3.75rem;
    border-right: 0;
    border-bottom: 1px solid var(--library-line);
  }

  .library-stat:last-child {
    border-bottom: 0;
  }

  .organize-summary {
    align-items: flex-start;
    flex-direction: column;
  }

  .organize-summary-meta {
    padding-left: 2.65rem;
  }

  .organize-grid {
    grid-template-columns: 1fr;
  }

  .organize-section + .organize-section {
    border-top: 1px solid var(--library-line);
    border-left: 0;
    padding-top: 1.25rem;
    padding-left: 0;
  }

  .document-row-actions {
    justify-content: flex-start;
    margin-top: 0.85rem;
  }

  .document-meta-line {
    padding-left: 0;
  }

  .document-editor-grid {
    grid-template-columns: 1fr;
  }

  .editor-field-wide,
  .document-editor-action {
    grid-column: auto;
  }
}

@media (prefers-reduced-motion: reduce) {
  .library-page *,
  .library-page *::before,
  .library-page *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    scroll-behavior: auto !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
