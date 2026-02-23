<script setup>
import { AlertTriangle, ShieldAlert, Info } from 'lucide-vue-next'
import { onMounted, ref, nextTick } from 'vue'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

gsap.registerPlugin(ScrollTrigger)

defineProps({ data: [Object, String] })

const severityMap = {
  high: { label: '高', cls: 'bg-red-100 text-red-700' },
  medium: { label: '中', cls: 'bg-amber-100 text-amber-700' },
  low: { label: '低', cls: 'bg-blue-100 text-blue-700' },
}

const containerRef = ref(null)

onMounted(async () => {
  await nextTick()
  if (containerRef.value) {
    const cards = containerRef.value.querySelectorAll('.gs-flaw')
    cards.forEach((card, index) => {
      gsap.fromTo(card,
        { x: -30, opacity: 0 },
        { 
          x: 0, opacity: 1, duration: 0.5, ease: 'power2.out',
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
</script>
<template>
  <div v-if="!data" class="text-text-muted">暂无数据</div>

  <div v-else-if="typeof data === 'string'" class="whitespace-pre-wrap text-sm leading-relaxed">{{ data }}</div>

  <div v-else class="space-y-4" ref="containerRef">
    <!-- 总评分 -->
    <div v-if="data.overall_rigor_score != null" class="flex items-center gap-3 p-4 bg-white border border-border rounded-lg">
      <ShieldAlert class="w-5 h-5 text-accent shrink-0" />
      <span class="text-sm">论证严谨度评分：</span>
      <span class="text-lg font-bold text-primary">{{ data.overall_rigor_score }}<span class="text-sm font-normal text-text-muted"> / 10</span></span>
    </div>

    <!-- 漏洞列表 -->
    <div v-for="(flaw, i) in (data.flaws || [])" :key="i"
      class="bg-white border border-border rounded-lg p-4 space-y-2 gs-flaw opacity-0">
      <div class="flex items-center gap-2 flex-wrap">
        <AlertTriangle class="w-4 h-4 shrink-0"
          :class="flaw.severity === 'high' ? 'text-red-500' : flaw.severity === 'medium' ? 'text-amber-500' : 'text-blue-500'" />
        <span class="font-medium text-sm">{{ flaw.type }}</span>
        <span class="text-xs text-text-muted">{{ flaw.location }}</span>
        <span v-if="flaw.severity && severityMap[flaw.severity]"
          class="text-xs px-2 py-0.5 rounded-full"
          :class="severityMap[flaw.severity].cls">
          {{ severityMap[flaw.severity].label }}
        </span>
      </div>
      <p class="text-sm text-text-secondary leading-relaxed">{{ flaw.description }}</p>
      <div v-if="flaw.suggestion" class="flex gap-2 text-sm bg-gray-50 rounded p-3">
        <Info class="w-4 h-4 text-accent shrink-0 mt-0.5" />
        <span class="text-text-muted">{{ flaw.suggestion }}</span>
      </div>
    </div>

    <!-- 总结 -->
    <div v-if="data.summary" class="p-4 bg-gray-50 border border-border rounded-lg text-sm text-text-secondary leading-relaxed">
      {{ data.summary }}
    </div>
  </div>
</template>
