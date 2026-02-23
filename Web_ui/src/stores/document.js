import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as api from '@/api'

export const useDocumentStore = defineStore('document', () => {
  const documents = ref([])
  const currentTask = ref(null)
  const loading = ref(false)

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

  async function startAnalysis(documentId) {
    const { data } = await api.startAnalysis(documentId)
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

  return { documents, currentTask, loading, fetchDocuments, upload, removeDocument, startAnalysis, pollTask }
})
