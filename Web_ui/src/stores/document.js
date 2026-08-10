import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as api from '@/api'

export const useDocumentStore = defineStore('document', () => {
  const documents = ref([])
  const currentTask = ref(null)
  const loading = ref(false)
  const referenceLibraries = ref([])
  const referenceDocuments = ref([])
  const referenceFolders = ref([])
  const referenceCategories = ref([])

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

  async function upload(file, onProgress) {
    const { data } = await api.uploadDocument(file, onProgress)
    return data
  }

  async function startAnalysis(documentId, mode, referenceLibraryIds = []) {
    const { data } = await api.startAnalysis(documentId, mode, referenceLibraryIds)
    currentTask.value = data
    return data
  }

  async function pollTask(taskId) {
    const { data } = await api.getTask(taskId)
    currentTask.value = data
    return data
  }

  async function removeDocument(id) {
    await api.deleteDocument(id)
    documents.value = documents.value.filter(d => d.id !== id)
  }

  async function fetchReferenceLibraries() {
    const { data } = await api.getReferenceLibraries()
    referenceLibraries.value = data
    return data
  }

  async function createReferenceLibrary(name) {
    const { data } = await api.createReferenceLibrary(name)
    referenceLibraries.value = [data, ...referenceLibraries.value]
    return data
  }

  async function removeReferenceLibrary(id) {
    await api.deleteReferenceLibrary(id)
    referenceLibraries.value = referenceLibraries.value.filter(item => item.id !== id)
  }

  async function fetchReferenceDocuments(libraryId, options = {}) {
    const { commit = true } = options
    const { data } = await api.getReferenceDocuments(libraryId)
    if (commit) {
      referenceDocuments.value = data
    }
    return data
  }

  async function updateReferenceDocument(id, payload) {
    const { data } = await api.updateReferenceDocument(id, payload)
    referenceDocuments.value = referenceDocuments.value.map(item => item.id === id ? data : item)
    return data
  }

  async function uploadReferenceFile(libraryId, file, onProgress) {
    const { data } = await api.uploadReferenceDocument(libraryId, file, onProgress)
    return data
  }

  async function removeReferenceDocument(id) {
    await api.deleteReferenceDocument(id)
    referenceDocuments.value = referenceDocuments.value.filter(item => item.id !== id)
  }

  async function fetchReferenceFolders(libraryId, options = {}) {
    const { commit = true } = options
    const { data } = await api.getReferenceFolders(libraryId)
    if (commit) {
      referenceFolders.value = data
    }
    return data
  }

  async function createReferenceFolder(libraryId, name) {
    const { data } = await api.createReferenceFolder(libraryId, name)
    referenceFolders.value = [...referenceFolders.value, data]
    return data
  }

  async function updateReferenceFolder(id, name) {
    const { data } = await api.updateReferenceFolder(id, name)
    referenceFolders.value = referenceFolders.value.map(item => item.id === id ? data : item)
    return data
  }

  async function removeReferenceFolder(id) {
    await api.deleteReferenceFolder(id)
    referenceFolders.value = referenceFolders.value.filter(item => item.id !== id)
  }

  async function fetchReferenceCategories(libraryId, options = {}) {
    const { commit = true } = options
    const { data } = await api.getReferenceCategories(libraryId)
    if (commit) {
      referenceCategories.value = data
    }
    return data
  }

  async function createReferenceCategory(libraryId, name) {
    const { data } = await api.createReferenceCategory(libraryId, name)
    referenceCategories.value = [...referenceCategories.value, data]
    return data
  }

  async function updateReferenceCategory(id, name) {
    const { data } = await api.updateReferenceCategory(id, name)
    referenceCategories.value = referenceCategories.value.map(item => item.id === id ? data : item)
    return data
  }

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
