<script setup>
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

onMounted(() => {
  if (container.value) {
    const elements = container.value.querySelectorAll('.gs-reveal')
    gsap.fromTo(elements,
      { y: 30, opacity: 0, scale: 0.98 },
      { y: 0, opacity: 1, scale: 1, duration: 0.8, stagger: 0.15, ease: 'back.out(1.2)' }
    )
  }
})

function onDrop(e) {
  dragging.value = false
  const file = e.dataTransfer.files[0]
  if (file) handleUpload(file)
}

function onFileChange(e) {
  const file = e.target.files[0]
  if (file) handleUpload(file)
}

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
    error.value = '上传失败，请重试'
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div ref="container" class="flex flex-col items-center justify-center min-h-[60vh]">
    <h1 class="font-heading text-3xl font-bold mb-2 opacity-0 gs-reveal">上传研报 / 论文</h1>
    <p class="text-text-muted mb-8 opacity-0 gs-reveal">支持 PDF、DOCX、TXT，AI 自动提取论据链并交叉验证</p>

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
