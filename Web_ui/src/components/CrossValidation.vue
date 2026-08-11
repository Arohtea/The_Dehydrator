<script setup>
/**
 * 交叉验证结果展示组件。
 *
 * 每条论据默认折叠，展开后同时展示模型知识、参考资料证据和联网来源；外部链接
 * 只有在协议为 HTTP(S) 时才渲染为可点击链接，避免把模型输出直接当作任意导航地址。
 */
import { AlertCircle, CheckCircle, ChevronDown, ExternalLink, XCircle } from 'lucide-vue-next'
import { computed, ref, onMounted, nextTick } from 'vue'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

gsap.registerPlugin(ScrollTrigger)

const props = defineProps({
  data: Array,
  mode: {
    type: String,
    default: 'deep',
  },
})

const expanded = ref({})

/**
 * 切换指定论据的展开状态。
 *
 * @param {number} i 论据在结果数组中的索引。
 * @returns {void} 只更新当前组件的展开记录。
 */
const toggle = (i) => { expanded.value[i] = !expanded.value[i] }

const statusMap = {
  supported: { label: '已验证', icon: 'check', cls: 'text-green-600 bg-green-50' },
  contradicted: { label: '存疑', icon: 'x', cls: 'text-red-600 bg-red-50' },
  partially_supported: { label: '部分支持', icon: 'alert', cls: 'text-amber-600 bg-amber-50' },
  unverifiable: { label: '无法验证', icon: 'alert', cls: 'text-slate-600 bg-slate-100' },
}

const containerRef = ref(null)
const localEvidenceLabel = computed(() => '模型知识')
const localEvidenceFallback = computed(() => '未提供模型知识摘要')

/**
 * 检查来源地址是否允许作为新窗口链接展示。
 *
 * @param {string} url 模型返回的来源地址。
 * @returns {boolean} 仅标准 HTTP(S) 地址返回 true。
 */
function isSafeSourceUrl(url) {
  return /^https?:\/\//i.test(String(url || ''))
}

// 交叉验证卡片渲染后才绑定滚动触发器，保证每条证据在进入视口时平滑出现。
onMounted(async () => {
  await nextTick()
  if (containerRef.value) {
    const cards = containerRef.value.querySelectorAll('.gs-cv-item')
    cards.forEach((card, index) => {
      gsap.fromTo(card,
        { scale: 0.95, opacity: 0, y: 20 },
        { 
          scale: 1, opacity: 1, y: 0, duration: 0.6, ease: 'back.out(1.2)',
          scrollTrigger: {
            trigger: card,
            start: 'top 95%',
            toggleActions: 'play none none none'
          }
        }
      )
    })
  }
})

/**
 * 初始化展开区域的起始样式，供 Vue transition 在插入时测量高度。
 *
 * @param {HTMLElement} el 即将展开的 DOM 节点。
 * @returns {void} 设置动画初始样式。
 */
const beforeEnter = (el) => { el.style.height = '0'; el.style.opacity = '0' }

/**
 * 使用 GSAP 展开证据详情，并在动画结束后通知 Vue transition 完成。
 *
 * @param {HTMLElement} el 正在展开的 DOM 节点。
 * @param {() => void} done Vue transition 完成回调。
 * @returns {void} 启动展开动画。
 */
const enter = (el, done) => {
  gsap.to(el, { height: 'auto', opacity: 1, duration: 0.4, ease: 'power2.out', onComplete: done })
}

/**
 * 使用 GSAP 收起证据详情，并在动画结束后移除节点。
 *
 * @param {HTMLElement} el 正在收起的 DOM 节点。
 * @param {() => void} done Vue transition 完成回调。
 * @returns {void} 启动收起动画。
 */
const leave = (el, done) => {
  gsap.to(el, { height: 0, opacity: 0, duration: 0.3, ease: 'power2.in', onComplete: done })
}
</script>

<template>
  <div v-if="!props.data" class="text-text-muted">结果不可用</div>

  <div v-else class="space-y-4" ref="containerRef">
    <div v-for="(item, i) in props.data" :key="i"
      class="bg-white border border-border rounded-lg overflow-hidden gs-cv-item">
      <!-- 标题栏 -->
      <div class="px-4 py-3 flex items-center gap-2 cursor-pointer hover:bg-gray-50" @click="toggle(i)">
        <component :is="item.verification_status === 'contradicted' ? XCircle : item.verification_status === 'supported' ? CheckCircle : AlertCircle"
          class="w-4 h-4 shrink-0"
          :class="(statusMap[item.verification_status] || statusMap.unverifiable).cls.split(' ')[0]" />
        <p class="font-medium text-sm flex-1 min-w-0">{{ item.claim || '待核验论据' }}</p>
        <span v-if="item.confidence != null" class="text-xs text-text-muted shrink-0">{{ Math.round(item.confidence * 100) }}%</span>
        <span v-if="item.verification_status"
          class="text-xs px-2 py-0.5 rounded-full shrink-0"
          :class="(statusMap[item.verification_status] || statusMap.unverifiable).cls">
          {{ (statusMap[item.verification_status] || { label: item.verification_status }).label }}
        </span>
        <ChevronDown class="w-4 h-4 text-text-muted shrink-0 transition-transform" :class="{ 'rotate-180': expanded[i] }" />
      </div>

      <!-- 展开内容 -->
      <transition @before-enter="beforeEnter" @enter="enter" @leave="leave" :css="false">
        <div v-if="expanded[i]" class="border-t border-border overflow-hidden">
          <div class="grid grid-cols-1 md:grid-cols-2 divide-y md:divide-y-0 md:divide-x divide-border">
            <div class="p-4">
              <div class="flex items-center gap-1.5 mb-2">
                <CheckCircle class="w-4 h-4 text-green-500" />
                <span class="text-xs font-medium text-green-700">{{ localEvidenceLabel }}</span>
              </div>
              <p class="text-sm text-text-muted leading-relaxed">{{ item.local_evidence_summary || localEvidenceFallback }}</p>
            </div>
            <div class="p-4">
              <div class="flex items-center gap-1.5 mb-2">
                <AlertCircle class="w-4 h-4 text-blue-500" />
                <span class="text-xs font-medium text-blue-700">联网来源</span>
              </div>
              <div v-if="item.web_sources?.length" class="mt-3 space-y-3">
                <div v-for="(source, j) in item.web_sources" :key="source.url || `${source.title}-${j}`" class="border-t border-border pt-3">
                  <p class="text-sm font-medium text-text">{{ source.title || '未命名网页' }}</p>
                  <a
                    v-if="isSafeSourceUrl(source.url)"
                    :href="source.url"
                    target="_blank"
                    rel="noreferrer noopener"
                    class="mt-1 flex items-start gap-1.5 break-all text-xs text-primary hover:text-primary-light"
                  >
                    <span class="min-w-0">{{ source.url }}</span>
                    <ExternalLink class="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  </a>
                  <p v-else class="mt-1 break-all text-xs text-text-muted">{{ source.url || '未提供链接' }}</p>
                </div>
              </div>
              <p v-else class="text-sm text-text-muted">{{ mode === 'quick' ? '快速分析未执行联网验证' : '未找到可展示的联网来源' }}</p>
            </div>
          </div>

          <div class="px-4 py-3 border-t border-border">
            <div class="flex items-center gap-1.5 mb-2">
              <AlertCircle class="w-4 h-4 text-accent" />
              <span class="text-xs font-medium text-accent">参考资料证据</span>
            </div>
            <p class="text-sm text-text-muted leading-relaxed">{{ item.reference_evidence_summary || '未提供参考资料' }}</p>
          </div>

          <!-- 矛盾点 -->
          <div v-if="item.contradictions?.length" class="px-4 py-3 border-t border-border">
            <p class="text-xs font-medium text-red-700 mb-1">矛盾点</p>
            <ul class="text-sm text-text-muted space-y-1">
              <li v-for="(c, j) in item.contradictions" :key="j">• {{ c }}</li>
            </ul>
          </div>

          <!-- 补充信息 -->
          <div v-if="item.supplements?.length" class="px-4 py-3 border-t border-border">
            <p class="text-xs font-medium text-accent mb-1">补充信息</p>
            <ul class="text-sm text-text-muted space-y-1">
              <li v-for="(s, j) in item.supplements" :key="j">• {{ s }}</li>
            </ul>
          </div>

          <!-- 结论 -->
          <div v-if="item.conclusion" class="px-4 py-3 border-t border-border bg-gray-50">
            <p class="text-sm text-text-secondary leading-relaxed">{{ item.conclusion }}</p>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>
