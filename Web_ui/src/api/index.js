import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

api.interceptors.response.use(
  response => response,
  error => {
    const requestUrl = error.config?.url || ''
    if (error.response?.status === 401 && !requestUrl.startsWith('/auth/')) {
      window.location.assign(`/login?redirect=${encodeURIComponent(window.location.pathname)}`)
    }
    return Promise.reject(error)
  },
)

export const getCsrfToken = () => api.get('/auth/csrf')

export const login = async (username, password) => {
  await getCsrfToken()
  return api.post('/auth/login', { username, password })
}

export const getCurrentUser = () => api.get('/auth/me')

export const logout = () => api.post('/auth/logout')

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

export const startAnalysis = (documentId, mode, referenceLibraryIds = []) =>
  api.post('/analysis/start', { documentId, mode, referenceLibraryIds })

export const getTask = (taskId) => api.get(`/analysis/task/${taskId}`)

export const getTasksByDocument = (documentId) =>
  api.get(`/analysis/document/${documentId}`)

export const cancelTask = (taskId) =>
  api.post(`/analysis/task/${taskId}/cancel`)

export const getSettings = () => api.get('/settings')

export const saveSettings = (data) => api.put('/settings', data)

export const getReferenceLibraries = () => api.get('/reference-libraries')

export const createReferenceLibrary = (name) =>
  api.post('/reference-libraries', { name })

export const deleteReferenceLibrary = (id) =>
  api.delete(`/reference-libraries/${id}`)

export const getReferenceDocuments = (libraryId) =>
  api.get(`/reference-libraries/${libraryId}/documents`)

export const updateReferenceDocument = (id, data) =>
  api.put(`/reference-documents/${id}`, data)

export const uploadReferenceDocument = (libraryId, file, onProgress) => {
  const form = new FormData()
  form.append('file', file)
  return api.post(`/reference-libraries/${libraryId}/documents/upload`, form, {
    onUploadProgress: (e) => onProgress?.(Math.round((e.loaded * 100) / e.total)),
  })
}

export const deleteReferenceDocument = (id) =>
  api.delete(`/reference-documents/${id}`)

export const getReferenceFolders = (libraryId) =>
  api.get(`/reference-libraries/${libraryId}/folders`)

export const createReferenceFolder = (libraryId, name) =>
  api.post(`/reference-libraries/${libraryId}/folders`, { name })

export const updateReferenceFolder = (id, name) =>
  api.put(`/reference-folders/${id}`, { name })

export const deleteReferenceFolder = (id) =>
  api.delete(`/reference-folders/${id}`)

export const getReferenceCategories = (libraryId) =>
  api.get(`/reference-libraries/${libraryId}/categories`)

export const createReferenceCategory = (libraryId, name) =>
  api.post(`/reference-libraries/${libraryId}/categories`, { name })

export const updateReferenceCategory = (id, name) =>
  api.put(`/reference-categories/${id}`, { name })

export const deleteReferenceCategory = (id) =>
  api.delete(`/reference-categories/${id}`)
