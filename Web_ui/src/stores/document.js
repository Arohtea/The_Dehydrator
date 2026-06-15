import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as api from '@/api'

export const useDocumentStore = defineStore('document', () => {
  const documents = ref([])
  const currentTask = ref(null)
  const loading = ref(false)
  const referenceLibraries = ref([])
  const referenceDocuments = ref([])

  async function fetchDocuments() {
    loading.value = true
    try {
      const { data } = await api.getDocuments()
      documents.value = data
    } finally {
      loading.value = false
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

  async function fetchReferenceDocuments(libraryId) {
    const { data } = await api.getReferenceDocuments(libraryId)
    referenceDocuments.value = data
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

  return {
    documents,
    currentTask,
    loading,
    referenceLibraries,
    referenceDocuments,
    fetchDocuments,
    upload,
    removeDocument,
    startAnalysis,
    pollTask,
    fetchReferenceLibraries,
    createReferenceLibrary,
    removeReferenceLibrary,
    fetchReferenceDocuments,
    uploadReferenceFile,
    removeReferenceDocument,
  }
})
