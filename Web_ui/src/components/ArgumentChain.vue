<script setup>
import { computed, onMounted, ref, nextTick } from 'vue'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

gsap.registerPlugin(ScrollTrigger)

const props = defineProps({ data: [Object, String] })
const title = computed(() => props.data?.title)
const mainClaim = computed(() => props.data?.main_conclusion || props.data?.mainClaim)
const steps = computed(() => props.data?.argument_chain || props.data?.arguments)
const containerRef = ref(null)

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
  <div v-if="!data" class="text-text-muted">暂无数据</div>

  <div v-else-if="typeof data === 'string'" class="whitespace-pre-wrap text-sm leading-relaxed">{{ data }}</div>

  <div v-else class="space-y-4" ref="containerRef">
    <p v-if="title" class="text-lg font-heading font-semibold">{{ title }}</p>

    <div v-if="mainClaim" class="bg-primary/5 border border-primary/20 rounded-lg p-4">
      <p class="text-xs text-primary font-medium mb-1">核心论点</p>
      <p class="font-heading text-base font-semibold">{{ mainClaim }}</p>
    </div>

    <div v-if="steps?.length" class="space-y-1">
      <template v-for="(arg, i) in steps" :key="i">
        <div class="bg-white border border-border rounded-lg p-4 gs-step opacity-0">
          <div class="flex items-start gap-3">
            <span class="shrink-0 w-6 h-6 rounded-full bg-primary text-white text-xs flex items-center justify-center font-medium">
              {{ arg.step || i + 1 }}
            </span>
            <div class="min-w-0">
              <p class="font-medium mb-1">{{ arg.claim || arg }}</p>
              <p v-if="arg.evidence" class="text-sm text-text-muted">{{ arg.evidence }}</p>
              <p v-if="arg.reasoning" class="text-xs text-text-muted mt-1 italic">{{ arg.reasoning }}</p>
            </div>
          </div>
        </div>
        <div v-if="arg.relation_to_next && arg.relation_to_next !== '无' && i < steps.length - 1"
          class="flex justify-center py-1">
          <span class="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-text-muted">{{ arg.relation_to_next }} ↓</span>
        </div>
      </template>
    </div>
  </div>
</template>
