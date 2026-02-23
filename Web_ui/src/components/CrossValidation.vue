<script setup>
import { CheckCircle, XCircle, AlertCircle, ChevronDown } from 'lucide-vue-next'
import { ref, onMounted, nextTick } from 'vue'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

gsap.registerPlugin(ScrollTrigger)

defineProps({ data: [Array, String] })

const expanded = ref({})
const toggle = (i) => { expanded.value[i] = !expanded.value[i] }

const statusMap = {
  supported: { label: '已验证', icon: 'check', cls: 'text-green-600 bg-green-50' },
  contradicted: { label: '存疑', icon: 'x', cls: 'text-red-600 bg-red-50' },
  partially_supported: { label: '部分支持', icon: 'alert', cls: 'text-amber-600 bg-amber-50' },
}

const containerRef = ref(null)

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

// 为展开收起动作添加平滑效果
const beforeEnter = (el) => { el.style.height = '0'; el.style.opacity = '0' }
const enter = (el, done) => {
  gsap.to(el, { height: 'auto', opacity: 1, duration: 0.4, ease: 'power2.out', onComplete: done })
}
const leave = (el, done) => {
  gsap.to(el, { height: 0, opacity: 0, duration: 0.3, ease: 'power2.in', onComplete: done })
}
</script>

<template>
  <div v-if="!data" class="text-text-muted">暂无数据</div>

  <div v-else-if="typeof data === 'string'" class="whitespace-pre-wrap text-sm leading-relaxed">{{ data }}</div>

  <div v-else class="space-y-4" ref="containerRef">
    <div v-for="(item, i) in data" :key="i"
      class="bg-white border border-border rounded-lg overflow-hidden gs-cv-item opacity-0">
      <!-- 标题栏 -->
      <div class="px-4 py-3 flex items-center gap-2 cursor-pointer hover:bg-gray-50" @click="toggle(i)">
        <component :is="item.verification_status === 'contradicted' ? XCircle : item.verification_status === 'supported' ? CheckCircle : AlertCircle"
          class="w-4 h-4 shrink-0"
          :class="(statusMap[item.verification_status] || statusMap.supported).cls.split(' ')[0]" />
        <p class="font-medium text-sm flex-1 min-w-0">{{ item.claim || `观点 ${i + 1}` }}</p>
        <span v-if="item.confidence != null" class="text-xs text-text-muted shrink-0">{{ Math.round(item.confidence * 100) }}%</span>
        <span v-if="item.verification_status"
          class="text-xs px-2 py-0.5 rounded-full shrink-0"
          :class="(statusMap[item.verification_status] || statusMap.supported).cls">
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
                <span class="text-xs font-medium text-green-700">本地证据</span>
              </div>
              <p class="text-sm text-text-muted leading-relaxed">{{ item.local_evidence_summary || '无' }}</p>
            </div>
            <div class="p-4">
              <div class="flex items-center gap-1.5 mb-2">
                <AlertCircle class="w-4 h-4 text-blue-500" />
                <span class="text-xs font-medium text-blue-700">联网证据</span>
              </div>
              <p class="text-sm text-text-muted leading-relaxed">{{ item.web_evidence_summary || '无' }}</p>
            </div>
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
