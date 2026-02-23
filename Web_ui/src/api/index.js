import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

export const uploadDocument = (file, onProgress) => {
  const form = new FormData()
  form.append('file', file)
  return api.post('/documents/upload', form, {
    onUploadProgress: (e) => onProgress?.(Math.round((e.loaded * 100) / e.total)),
  })
}

export const getDocuments = () => api.get('/documents')

export const getDocument = (id) => api.get(`/documents/${id}`)

export const deleteDocument = (id) => api.delete(`/documents/${id}`)

export const startAnalysis = (documentId) =>
  api.post('/analysis/start', { documentId })

export const getTask = (taskId) => api.get(`/analysis/task/${taskId}`)

export const getTasksByDocument = (documentId) =>
  api.get(`/analysis/document/${documentId}`)

export const cancelTask = (taskId) =>
  api.post(`/analysis/task/${taskId}/cancel`)

export const getSettings = () => api.get('/settings')

export const saveSettings = (data) => api.put('/settings', data)
