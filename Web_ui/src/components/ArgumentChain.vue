<script setup>
/**
 * 论据链结果展示组件。
 *
 * 组件兼容后端不同版本的字段命名，并把每条论据的核心主张、证据、推理依据及关联
 * 逻辑漏洞分层展示；它只负责呈现已经由分析服务生成的结果，不在前端重新推断结论。
 */
import { computed, onMounted, ref, nextTick } from 'vue'
import { AlertTriangle } from 'lucide-vue-next'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

gsap.registerPlugin(ScrollTrigger)

const props = defineProps({ data: Object })
const title = computed(() => props.data?.title)
const mainClaim = computed(() => props.data?.main_conclusion || props.data?.mainClaim)
const steps = computed(() => props.data?.argument_chain || props.data?.arguments)
const containerRef = ref(null)

const flawSeverityMap = {
  high: { label: '高风险', cls: 'text-red-700 bg-red-50 border-red-100' },
  medium: { label: '中风险', cls: 'text-amber-700 bg-amber-50 border-amber-100' },
  low: { label: '低风险', cls: 'text-blue-700 bg-blue-50 border-blue-100' },
}

/**
 * 读取论据主张，并兼容旧结果中直接使用字符串的条目。
 *
 * @param {string|Object} arg 论据条目。
 * @param {number} index 论据在列表中的零基索引。
 * @returns {string} 可展示的主张文本。
 */
function getClaim(arg, index) {
  if (typeof arg === 'string') return arg
  return arg?.claim || `第 ${index + 1} 条论据`
}

/**
 * 将逻辑漏洞数量转换为自然语言提示。
 *
 * @param {Object} arg 论据条目。
 * @returns {string} 漏洞数量提示。
 */
function flawLabel(arg) {
  const count = Number(arg?.logic_flaw_count) || 1
  return count > 1 ? `${count} 处逻辑漏洞` : '存在逻辑漏洞'
}

/**
 * 将后端风险等级映射为徽标文本和颜色，并为未知值使用低风险样式兜底。
 *
 * @param {Object} arg 论据条目。
 * @returns {{label: string, cls: string}} 风险展示配置。
 */
function flawSeverity(arg) {
  return flawSeverityMap[arg?.logic_flaw_severity] || flawSeverityMap.low
}

// 论据卡片只有在 v-for 完成渲染后才能绑定滚动触发动画。
onMounted(async () => {
  await nextTick()
  if (containerRef.value) {
    const cards = containerRef.value.querySelectorAll('.gs-step')
    cards.forEach((card, index) => {
      gsap.fromTo(card, 
        { y: 30, opacity: 0 },
        { 
          y: 0, opacity: 1, duration: 0.6, ease: 'power2.out',
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
  <div v-if="!data" class="text-text-muted">结果不可用</div>

  <div v-else class="space-y-4" ref="containerRef">
    <p v-if="title" class="text-lg font-heading font-semibold">{{ title }}</p>

    <div v-if="mainClaim" class="bg-primary/5 border border-primary/20 rounded-lg p-4">
      <p class="text-xs text-primary font-medium mb-1">核心论点</p>
      <p class="font-heading text-base font-semibold">{{ mainClaim }}</p>
    </div>

    <div v-if="steps?.length" class="space-y-1">
      <template v-for="(arg, i) in steps" :key="i">
        <div class="bg-white border border-border rounded-lg p-4 gs-step">
          <div class="flex items-start gap-3">
            <span class="shrink-0 w-6 h-6 rounded-full bg-primary text-white text-xs flex items-center justify-center font-medium">
              {{ arg?.step || i + 1 }}
            </span>
            <div class="min-w-0">
              <p class="mb-3 font-medium leading-relaxed">{{ getClaim(arg, i) }}</p>
              <div v-if="arg?.logic_flaw" class="mb-3 inline-flex items-center gap-1.5 rounded border px-2 py-1 text-xs" :class="flawSeverity(arg).cls">
                <AlertTriangle class="h-3.5 w-3.5 shrink-0" />
                <span>{{ flawLabel(arg) }}</span>
                <span>{{ flawSeverity(arg).label }}</span>
              </div>
              <div v-if="arg?.evidence" class="mb-2">
                <p class="mb-1 text-xs font-medium text-primary">证据/数据</p>
                <p class="text-sm leading-relaxed text-text-muted">{{ arg.evidence }}</p>
              </div>
              <div v-if="arg?.reasoning">
                <p class="mb-1 text-xs font-medium text-text-muted">推理依据</p>
                <p class="text-sm leading-relaxed text-text-muted">{{ arg.reasoning }}</p>
              </div>
            </div>
          </div>
        </div>
        <div v-if="arg?.relation_to_next && arg.relation_to_next !== '无' && i < steps.length - 1"
          class="flex justify-center py-1">
          <span class="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-text-muted">{{ arg.relation_to_next }} ↓</span>
        </div>
      </template>
    </div>
  </div>
</template>
