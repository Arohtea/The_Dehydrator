<script setup>
/**
 * 待分析文档上传页。
 *
 * 页面只负责确认用户选择了受支持的文件类型、展示浏览器上传进度并在服务端
 * 创建文档后回到列表；真正的解析和向量化由后端异步完成，因此这里不等待分析结果。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useDocumentStore } from '@/stores/document'
import { Upload, FileUp } from 'lucide-vue-next'
import gsap from 'gsap'

const router = useRouter()
const store = useDocumentStore()
const container = ref(null)

const dragging = ref(false)
const progress = ref(0)
const uploading = ref(false)
const error = ref('')

// 只给当前页面的内容做进场动画，不影响上传状态或路由跳转。
onMounted(() => {
  if (container.value) {
    const elements = container.value.querySelectorAll('.gs-reveal')
    gsap.fromTo(elements,
      { y: 30, opacity: 0, scale: 0.98 },
      { y: 0, opacity: 1, scale: 1, duration: 0.8, stagger: 0.15, ease: 'back.out(1.2)' }
    )
  }
})

/**
 * 处理拖拽释放，将第一个文件交给统一上传入口。
 *
 * @param {DragEvent} e 浏览器拖拽事件。
 * @returns {void} 没有文件时直接结束。
 */
function onDrop(e) {
  dragging.value = false
  const file = e.dataTransfer.files[0]
  if (file) handleUpload(file)
}

/**
 * 处理文件选择器变更，并复用拖拽上传所使用的校验与错误展示流程。
 *
 * @param {Event} e 文件输入框变更事件。
 * @returns {void} 没有选择文件时直接结束。
 */
function onFileChange(e) {
  const file = e.target.files[0]
  if (file) handleUpload(file)
}

/**
 * 校验并上传单个待分析文件。
 *
 * 扩展名校验在请求前提供即时反馈，进度回调只更新当前页的展示；上传成功后跳转
 * 到文档列表，由列表页负责展示文档进入向量化和分析状态的后续过程。
 *
 * @param {File} file 待上传的 PDF、DOCX 或 TXT 文件。
 * @returns {Promise<void>} 上传流程完成后返回。
 */
async function handleUpload(file) {
  const allowed = ['.pdf', '.docx', '.txt']
  const ext = file.name.slice(file.name.lastIndexOf('.')).toLowerCase()
  if (!allowed.includes(ext)) {
    error.value = '仅支持 PDF、DOCX、TXT 格式'
    return
  }
  error.value = ''
  uploading.value = true
  progress.value = 0
  try {
    await store.upload(file, (p) => { progress.value = p })
    router.push('/documents')
  } catch (e) {
    error.value = e?.response?.data?.error || '上传失败，请重试'
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div ref="container" class="flex flex-col items-center justify-center min-h-[60vh]">
    <h1 class="font-heading text-3xl font-bold mb-2 opacity-0 gs-reveal">上传研报 / 论文</h1>
    <p class="text-text-muted mb-8 opacity-0 gs-reveal">支持 PDF、DOCX、TXT，分析上传后会自动进入分析论文资料库，并由 AI 完成初步归类</p>

    <label
      class="w-full max-w-lg border-2 border-dashed rounded-xl p-12 flex flex-col items-center gap-4 cursor-pointer transition-colors duration-200 opacity-0 gs-reveal"
      :class="dragging ? 'border-primary bg-primary/5 scale-105' : 'border-border hover:border-primary-light'"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
    >
      <Upload v-if="!uploading" class="w-10 h-10 text-primary-light" />
      <FileUp v-else class="w-10 h-10 text-accent animate-pulse" />

      <span class="text-text-muted text-sm">
        {{ uploading ? '上传中...' : '拖拽文件到此处，或点击选择' }}
      </span>

      <input type="file" accept=".pdf,.docx,.txt" class="hidden" @change="onFileChange" :disabled="uploading" />

      <div v-if="uploading" class="w-full bg-border rounded-full h-2 mt-2">
        <div class="bg-primary h-2 rounded-full transition-all duration-300" :style="{ width: progress + '%' }"></div>
      </div>
    </label>

    <p v-if="error" class="text-red-500 text-sm mt-4">{{ error }}</p>
  </div>
</template>
