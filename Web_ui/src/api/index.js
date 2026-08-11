/**
 * 前端业务 API 入口。
 *
 * 所有请求都从这里经过，页面和 Pinia Store 不直接拼接认证、CSRF 或服务端路径，
 * 这样可以把会话失效和上传进度等跨页面行为集中维护，同时保持后端接口协议单一。
 */
import axios from 'axios'

// 统一使用同源 `/api`，并让浏览器自动携带会话 Cookie 与 CSRF 令牌。
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
    // 除登录接口外，任何未授权响应都回到登录页；当前路径会作为登录后的返回地址。
    const requestUrl = error.config?.url || ''
    if (error.response?.status === 401 && !requestUrl.startsWith('/auth/')) {
      window.location.assign(`/login?redirect=${encodeURIComponent(window.location.pathname)}`)
    }
    return Promise.reject(error)
  },
)

/**
 * 先访问后端的 CSRF 端点，让服务端写入后续写请求所需的令牌 Cookie。
 *
 * @returns {Promise<import('axios').AxiosResponse>} CSRF 初始化请求的响应。
 */
export const getCsrfToken = () => api.get('/auth/csrf')

/**
 * 使用管理员凭据创建登录会话。
 *
 * @param {string} username 管理员用户名。
 * @param {string} password 管理员密码。
 * @returns {Promise<import('axios').AxiosResponse>} 登录接口响应。
 */
export const login = async (username, password) => {
  await getCsrfToken()
  return api.post('/auth/login', { username, password })
}

/**
 * 查询当前会话对应的管理员，用于路由守卫确认会话仍然有效。
 *
 * @returns {Promise<import('axios').AxiosResponse>} 当前用户响应。
 */
export const getCurrentUser = () => api.get('/auth/me')

/**
 * 请求服务端销毁当前登录会话。
 *
 * @returns {Promise<import('axios').AxiosResponse>} 注销接口响应。
 */
export const logout = () => api.post('/auth/logout')

/**
 * 上传待分析文档，并把 Axios 的原始进度转换为百分比交给页面展示。
 *
 * @param {File} file 待上传的 PDF、DOCX 或 TXT 文件。
 * @param {(progress: number) => void} [onProgress] 上传进度回调，取值范围为 0 至 100。
 * @returns {Promise<import('axios').AxiosResponse>} 文档创建响应。
 */
export const uploadDocument = (file, onProgress) => {
  const form = new FormData()
  form.append('file', file)
  return api.post('/documents/upload', form, {
    onUploadProgress: (e) => onProgress?.(Math.round((e.loaded * 100) / e.total)),
  })
}

/**
 * 获取当前管理员可见的分析文档列表。
 *
 * @returns {Promise<import('axios').AxiosResponse>} 文档摘要列表响应。
 */
export const getDocuments = () => api.get('/documents')

/**
 * 获取单个文档的详情。
 *
 * @param {string|number} id 文档 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 文档详情响应。
 */
export const getDocument = (id) => api.get(`/documents/${id}`)

/**
 * 删除文档及其关联的分析结果和外部资源。
 *
 * @param {string|number} id 文档 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 删除操作响应。
 */
export const deleteDocument = (id) => api.delete(`/documents/${id}`)

/**
 * 为指定文档创建一次分析任务。
 *
 * @param {string|number} documentId 待分析文档 ID。
 * @param {'quick'|'deep'} mode 分析模式。
 * @param {(string|number)[]} [referenceLibraryIds=[]] 参与检索的资料库 ID 列表。
 * @returns {Promise<import('axios').AxiosResponse>} 新建任务响应。
 */
export const startAnalysis = (documentId, mode, referenceLibraryIds = []) =>
  api.post('/analysis/start', { documentId, mode, referenceLibraryIds })

/**
 * 获取单个分析任务的最新状态和结果字段。
 *
 * @param {string|number} taskId 分析任务 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 任务状态响应。
 */
export const getTask = (taskId) => api.get(`/analysis/task/${taskId}`)

/**
 * 获取某个文档的分析任务历史，用于恢复页面最近一次任务。
 *
 * @param {string|number} documentId 文档 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 任务列表响应。
 */
export const getTasksByDocument = (documentId) =>
  api.get(`/analysis/document/${documentId}`)

/**
 * 请求终止仍在运行的分析任务。
 *
 * @param {string|number} taskId 分析任务 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 终止后的任务状态响应。
 */
export const cancelTask = (taskId) =>
  api.post(`/analysis/task/${taskId}/cancel`)

/**
 * 获取 AI 模型、联网搜索和文本处理参数的脱敏配置。
 *
 * @returns {Promise<import('axios').AxiosResponse>} 设置响应，不包含明文密钥。
 */
export const getSettings = () => api.get('/settings')

/**
 * 保存管理员提交的 AI 运行设置。
 *
 * @param {Object} data 文本模型、向量模型、Tavily 和处理参数配置。
 * @returns {Promise<import('axios').AxiosResponse>} 保存操作响应。
 */
export const saveSettings = (data) => api.put('/settings', data)

/**
 * 获取当前管理员可用的资料库列表。
 *
 * @returns {Promise<import('axios').AxiosResponse>} 资料库列表响应。
 */
export const getReferenceLibraries = () => api.get('/reference-libraries')

/**
 * 创建一个新的参考资料库。
 *
 * @param {string} name 资料库名称。
 * @returns {Promise<import('axios').AxiosResponse>} 新资料库响应。
 */
export const createReferenceLibrary = (name) =>
  api.post('/reference-libraries', { name })

/**
 * 删除资料库；服务端会校验该资料库是否允许删除及是否为空。
 *
 * @param {string|number} id 资料库 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 删除操作响应。
 */
export const deleteReferenceLibrary = (id) =>
  api.delete(`/reference-libraries/${id}`)

/**
 * 获取指定资料库中的参考文档。
 *
 * @param {string|number} libraryId 资料库 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 参考文档列表响应。
 */
export const getReferenceDocuments = (libraryId) =>
  api.get(`/reference-libraries/${libraryId}/documents`)

/**
 * 更新参考文档的展示名称、文件夹和分类归属。
 *
 * @param {string|number} id 参考文档 ID。
 * @param {Object} data 要更新的资料属性。
 * @returns {Promise<import('axios').AxiosResponse>} 更新后的参考文档响应。
 */
export const updateReferenceDocument = (id, data) =>
  api.put(`/reference-documents/${id}`, data)

/**
 * 向指定资料库上传参考文档。
 *
 * @param {string|number} libraryId 目标资料库 ID。
 * @param {File} file 待上传的 PDF、DOCX 或 TXT 文件。
 * @param {(progress: number) => void} [onProgress] 上传进度回调。
 * @returns {Promise<import('axios').AxiosResponse>} 参考文档创建响应。
 */
export const uploadReferenceDocument = (libraryId, file, onProgress) => {
  const form = new FormData()
  form.append('file', file)
  return api.post(`/reference-libraries/${libraryId}/documents/upload`, form, {
    onUploadProgress: (e) => onProgress?.(Math.round((e.loaded * 100) / e.total)),
  })
}

/**
 * 删除参考文档及其向量资源。
 *
 * @param {string|number} id 参考文档 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 删除操作响应。
 */
export const deleteReferenceDocument = (id) =>
  api.delete(`/reference-documents/${id}`)

/**
 * 获取资料库中的文件夹定义。
 *
 * @param {string|number} libraryId 资料库 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 文件夹列表响应。
 */
export const getReferenceFolders = (libraryId) =>
  api.get(`/reference-libraries/${libraryId}/folders`)

/**
 * 在资料库中创建文件夹。
 *
 * @param {string|number} libraryId 资料库 ID。
 * @param {string} name 文件夹名称。
 * @returns {Promise<import('axios').AxiosResponse>} 新文件夹响应。
 */
export const createReferenceFolder = (libraryId, name) =>
  api.post(`/reference-libraries/${libraryId}/folders`, { name })

/**
 * 更新文件夹名称。
 *
 * @param {string|number} id 文件夹 ID。
 * @param {string} name 新名称。
 * @returns {Promise<import('axios').AxiosResponse>} 更新后的文件夹响应。
 */
export const updateReferenceFolder = (id, name) =>
  api.put(`/reference-folders/${id}`, { name })

/**
 * 删除文件夹，并由服务端处理其下资料的约束。
 *
 * @param {string|number} id 文件夹 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 删除操作响应。
 */
export const deleteReferenceFolder = (id) =>
  api.delete(`/reference-folders/${id}`)

/**
 * 获取资料库中的分类定义。
 *
 * @param {string|number} libraryId 资料库 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 分类列表响应。
 */
export const getReferenceCategories = (libraryId) =>
  api.get(`/reference-libraries/${libraryId}/categories`)

/**
 * 在资料库中创建分类。
 *
 * @param {string|number} libraryId 资料库 ID。
 * @param {string} name 分类名称。
 * @returns {Promise<import('axios').AxiosResponse>} 新分类响应。
 */
export const createReferenceCategory = (libraryId, name) =>
  api.post(`/reference-libraries/${libraryId}/categories`, { name })

/**
 * 更新分类名称。
 *
 * @param {string|number} id 分类 ID。
 * @param {string} name 新名称。
 * @returns {Promise<import('axios').AxiosResponse>} 更新后的分类响应。
 */
export const updateReferenceCategory = (id, name) =>
  api.put(`/reference-categories/${id}`, { name })

/**
 * 删除分类，并由服务端处理关联资料的约束。
 *
 * @param {string|number} id 分类 ID。
 * @returns {Promise<import('axios').AxiosResponse>} 删除操作响应。
 */
export const deleteReferenceCategory = (id) =>
  api.delete(`/reference-categories/${id}`)
