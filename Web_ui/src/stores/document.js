import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as api from '@/api'

/**
 * 文档与参考资料库的前端状态中心。
 *
 * 页面只通过本 Store 提交列表和资源变更，避免上传、分析启动以及资料库编辑后
 * 各页面分别维护一份容易失真的本地副本；`commit`/`silent` 选项用于处理切换
 * 资料库和后台状态刷新时的请求竞态与视觉抖动。
 */
export const useDocumentStore = defineStore('document', () => {
  const documents = ref([])
  const currentTask = ref(null)
  const loading = ref(false)
  const referenceLibraries = ref([])
  const referenceDocuments = ref([])
  const referenceFolders = ref([])
  const referenceCategories = ref([])

  /**
   * 拉取当前管理员的文档摘要列表。
   *
   * @param {{silent?: boolean}} [options={}] 是否只更新状态而不显示全局加载态。
   * @returns {Promise<Array>} 已校验为数组的文档列表。
   * @throws {Error} 服务端返回的文档列表不是数组时抛出格式错误。
   */
  async function fetchDocuments(options = {}) {
    const { silent = false } = options
    if (!silent) loading.value = true
    try {
      const { data } = await api.getDocuments()
      if (!Array.isArray(data)) {
        throw new Error('文档列表响应格式错误')
      }
      documents.value = data
    } finally {
      if (!silent) loading.value = false
    }
  }

  /**
   * 上传一个待分析文档，并把响应交给上传页继续处理。
   *
   * @param {File} file 待上传文件。
   * @param {(progress: number) => void} [onProgress] 上传进度回调。
   * @returns {Promise<Object>} 服务端创建的文档摘要。
   */
  async function upload(file, onProgress) {
    const { data } = await api.uploadDocument(file, onProgress)
    return data
  }

  /**
   * 启动文档分析，并把新任务设置为当前任务供结果页使用。
   *
   * @param {string|number} documentId 文档 ID。
   * @param {'quick'|'deep'} mode 分析模式。
   * @param {(string|number)[]} [referenceLibraryIds=[]] 选中的参考资料库 ID。
   * @returns {Promise<Object>} 新建的分析任务。
   */
  async function startAnalysis(documentId, mode, referenceLibraryIds = []) {
    const { data } = await api.startAnalysis(documentId, mode, referenceLibraryIds)
    currentTask.value = data
    return data
  }

  /**
   * 获取任务最新状态，用于结果页的定时兜底同步。
   *
   * @param {string|number} taskId 分析任务 ID。
   * @returns {Promise<Object>} 最新任务状态。
   */
  async function pollTask(taskId) {
    const { data } = await api.getTask(taskId)
    currentTask.value = data
    return data
  }

  /**
   * 删除文档并立即从当前列表移除；服务端负责等待分析终止和清理外部资源。
   *
   * @param {string|number} id 文档 ID。
   * @returns {Promise<void>} 删除完成后返回。
   */
  async function removeDocument(id) {
    await api.deleteDocument(id)
    documents.value = documents.value.filter(d => d.id !== id)
  }

  /**
   * 获取资料库列表并提交到 Store，返回值用于首次自动选择资料库。
   *
   * @returns {Promise<Array>} 资料库列表。
   */
  async function fetchReferenceLibraries() {
    const { data } = await api.getReferenceLibraries()
    referenceLibraries.value = data
    return data
  }

  /**
   * 创建资料库并把新资料库置于本地列表顶部。
   *
   * @param {string} name 资料库名称。
   * @returns {Promise<Object>} 新创建的资料库。
   */
  async function createReferenceLibrary(name) {
    const { data } = await api.createReferenceLibrary(name)
    referenceLibraries.value = [data, ...referenceLibraries.value]
    return data
  }

  /**
   * 删除资料库，并同步清理本地列表中的对应条目。
   *
   * @param {string|number} id 资料库 ID。
   * @returns {Promise<void>} 删除完成后返回。
   */
  async function removeReferenceLibrary(id) {
    await api.deleteReferenceLibrary(id)
    referenceLibraries.value = referenceLibraries.value.filter(item => item.id !== id)
  }

  /**
   * 获取指定资料库的参考文档。
   *
   * @param {string|number} libraryId 资料库 ID。
   * @param {{commit?: boolean}} [options={}] 是否提交到共享的参考文档状态。
   * @returns {Promise<Array>} 参考文档列表。
   */
  async function fetchReferenceDocuments(libraryId, options = {}) {
    const { commit = true } = options
    const { data } = await api.getReferenceDocuments(libraryId)
    if (commit) {
      referenceDocuments.value = data
    }
    return data
  }

  /**
   * 更新参考文档属性，并用服务端返回对象替换本地旧对象。
   *
   * @param {string|number} id 参考文档 ID。
   * @param {Object} payload 展示名称、文件夹和分类等更新字段。
   * @returns {Promise<Object>} 更新后的参考文档。
   */
  async function updateReferenceDocument(id, payload) {
    const { data } = await api.updateReferenceDocument(id, payload)
    referenceDocuments.value = referenceDocuments.value.map(item => item.id === id ? data : item)
    return data
  }

  /**
   * 向指定资料库上传参考文件。
   *
   * @param {string|number} libraryId 目标资料库 ID。
   * @param {File} file 待上传文件。
   * @param {(progress: number) => void} [onProgress] 上传进度回调。
   * @returns {Promise<Object>} 新创建的参考文档。
   */
  async function uploadReferenceFile(libraryId, file, onProgress) {
    const { data } = await api.uploadReferenceDocument(libraryId, file, onProgress)
    return data
  }

  /**
   * 删除参考文档，并从当前资料库文档列表中移除。
   *
   * @param {string|number} id 参考文档 ID。
   * @returns {Promise<void>} 删除完成后返回。
   */
  async function removeReferenceDocument(id) {
    await api.deleteReferenceDocument(id)
    referenceDocuments.value = referenceDocuments.value.filter(item => item.id !== id)
  }

  /**
   * 获取指定资料库的文件夹列表。
   *
   * @param {string|number} libraryId 资料库 ID。
   * @param {{commit?: boolean}} [options={}] 是否提交到共享的文件夹状态。
   * @returns {Promise<Array>} 文件夹列表。
   */
  async function fetchReferenceFolders(libraryId, options = {}) {
    const { commit = true } = options
    const { data } = await api.getReferenceFolders(libraryId)
    if (commit) {
      referenceFolders.value = data
    }
    return data
  }

  /**
   * 创建文件夹并追加到当前资料库的文件夹状态。
   *
   * @param {string|number} libraryId 资料库 ID。
   * @param {string} name 文件夹名称。
   * @returns {Promise<Object>} 新创建的文件夹。
   */
  async function createReferenceFolder(libraryId, name) {
    const { data } = await api.createReferenceFolder(libraryId, name)
    referenceFolders.value = [...referenceFolders.value, data]
    return data
  }

  /**
   * 更新文件夹名称并替换本地对应条目。
   *
   * @param {string|number} id 文件夹 ID。
   * @param {string} name 新名称。
   * @returns {Promise<Object>} 更新后的文件夹。
   */
  async function updateReferenceFolder(id, name) {
    const { data } = await api.updateReferenceFolder(id, name)
    referenceFolders.value = referenceFolders.value.map(item => item.id === id ? data : item)
    return data
  }

  /**
   * 删除文件夹，并从当前资料库的文件夹状态中移除。
   *
   * @param {string|number} id 文件夹 ID。
   * @returns {Promise<void>} 删除完成后返回。
   */
  async function removeReferenceFolder(id) {
    await api.deleteReferenceFolder(id)
    referenceFolders.value = referenceFolders.value.filter(item => item.id !== id)
  }

  /**
   * 获取指定资料库的分类列表。
   *
   * @param {string|number} libraryId 资料库 ID。
   * @param {{commit?: boolean}} [options={}] 是否提交到共享的分类状态。
   * @returns {Promise<Array>} 分类列表。
   */
  async function fetchReferenceCategories(libraryId, options = {}) {
    const { commit = true } = options
    const { data } = await api.getReferenceCategories(libraryId)
    if (commit) {
      referenceCategories.value = data
    }
    return data
  }

  /**
   * 创建分类并追加到当前资料库的分类状态。
   *
   * @param {string|number} libraryId 资料库 ID。
   * @param {string} name 分类名称。
   * @returns {Promise<Object>} 新创建的分类。
   */
  async function createReferenceCategory(libraryId, name) {
    const { data } = await api.createReferenceCategory(libraryId, name)
    referenceCategories.value = [...referenceCategories.value, data]
    return data
  }

  /**
   * 更新分类名称并替换本地对应条目。
   *
   * @param {string|number} id 分类 ID。
   * @param {string} name 新名称。
   * @returns {Promise<Object>} 更新后的分类。
   */
  async function updateReferenceCategory(id, name) {
    const { data } = await api.updateReferenceCategory(id, name)
    referenceCategories.value = referenceCategories.value.map(item => item.id === id ? data : item)
    return data
  }

  /**
   * 删除分类，并从当前资料库的分类状态中移除。
   *
   * @param {string|number} id 分类 ID。
   * @returns {Promise<void>} 删除完成后返回。
   */
  async function removeReferenceCategory(id) {
    await api.deleteReferenceCategory(id)
    referenceCategories.value = referenceCategories.value.filter(item => item.id !== id)
  }

  return {
    documents,
    currentTask,
    loading,
    referenceLibraries,
    referenceDocuments,
    referenceFolders,
    referenceCategories,
    fetchDocuments,
    upload,
    removeDocument,
    startAnalysis,
    pollTask,
    fetchReferenceLibraries,
    createReferenceLibrary,
    removeReferenceLibrary,
    fetchReferenceDocuments,
    updateReferenceDocument,
    uploadReferenceFile,
    removeReferenceDocument,
    fetchReferenceFolders,
    createReferenceFolder,
    updateReferenceFolder,
    removeReferenceFolder,
    fetchReferenceCategories,
    createReferenceCategory,
    updateReferenceCategory,
    removeReferenceCategory,
  }
})
