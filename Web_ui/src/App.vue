<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { FileText, Upload, List, Settings, Layers, Sparkles } from 'lucide-vue-next'
import Lenis from '@studio-freight/lenis'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

gsap.registerPlugin(ScrollTrigger)

const navRef = ref(null)
const mainRef = ref(null)
let lenis = null

onMounted(() => {
  // 1. 初始化 Lenis 平滑滚动
  lenis = new Lenis({
    duration: 1.2,
    easing: (t) => Math.min(1, 1.001 - Math.pow(2, -10 * t)),
    orientation: 'vertical',
    gestureOrientation: 'vertical',
    smoothWheel: true,
    wheelMultiplier: 1,
    smoothTouch: false,
    touchMultiplier: 2,
    infinite: false,
  })

  // 2. 将 Lenis 的滚动事件同步给 ScrollTrigger
  lenis.on('scroll', ScrollTrigger.update)

  // 3. 将 Lenis 集成到 GSAP 主时间轴 ticker
  gsap.ticker.add((time) => {
    lenis.raf(time * 1000)
  })
  gsap.ticker.lagSmoothing(0)

  // 4. 全局首屏进场动画
  const tl = gsap.timeline()
  
  if (navRef.value) {
    tl.fromTo(navRef.value,
      { y: -100, opacity: 0 },
      { y: 0, opacity: 1, duration: 1, ease: 'power3.out' }
    )
  }
  if (mainRef.value) {
    tl.fromTo(mainRef.value,
      { y: 40, opacity: 0 },
      { y: 0, opacity: 1, duration: 1, ease: 'power3.out' },
      '-=0.6'
    )
  }
})

onUnmounted(() => {
  if (lenis) {
    lenis.destroy()
    gsap.ticker.remove((time) => lenis.raf(time * 1000))
  }
})
</script>

<template>
  <div class="min-h-screen bg-bg font-body text-text">
    <nav ref="navRef" class="fixed top-4 left-4 right-4 z-50 bg-white/90 backdrop-blur-md border border-border rounded-xl px-6 py-3 flex items-center justify-between opacity-0 shadow-sm shadow-black/5">
      <RouterLink to="/" class="flex items-center gap-2.5 cursor-pointer group">
        <div class="relative flex items-center justify-center w-8 h-8 rounded-lg bg-gradient-to-tr from-accent to-accent-light shadow-md shadow-accent/20 transition-transform duration-300 group-hover:scale-105">
          <Layers class="w-4 h-4 text-white absolute" stroke-width="2.5" />
          <Sparkles class="w-3 h-3 text-white/80 absolute top-1 right-1 opacity-0 group-hover:opacity-100 transition-opacity duration-300 animate-pulse" />
        </div>
        <div class="flex flex-col">
          <span class="font-heading text-lg font-bold tracking-tight text-text leading-none">The Dehydrator</span>
          <span class="text-[10px] font-medium text-accent uppercase tracking-widest mt-0.5">Content Refinement</span>
        </div>
      </RouterLink>
      <div class="flex gap-4">
        <RouterLink to="/" class="flex items-center gap-1.5 text-sm text-text-muted hover:text-primary transition-colors duration-200 cursor-pointer">
          <Upload class="w-4 h-4" />
          <span>上传</span>
        </RouterLink>
        <RouterLink to="/documents" class="flex items-center gap-1.5 text-sm text-text-muted hover:text-primary transition-colors duration-200 cursor-pointer">
          <List class="w-4 h-4" />
          <span>文档</span>
        </RouterLink>
        <RouterLink to="/settings" class="flex items-center gap-1.5 text-sm text-text-muted hover:text-primary transition-colors duration-200 cursor-pointer">
          <Settings class="w-4 h-4" />
          <span>设置</span>
        </RouterLink>
      </div>
    </nav>
    <main ref="mainRef" class="pt-26 px-4 pb-8 max-w-5xl mx-auto opacity-0">
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>
  </div>
</template>

<style>
.page-enter-active,
.page-leave-active {
  transition: opacity 0.35s ease, transform 0.35s ease;
}
.page-enter-from,
.page-leave-to {
  opacity: 0;
  transform: translateY(12px);
}

html.lenis, html.lenis body {
  height: auto;
}
.lenis.lenis-smooth {
  scroll-behavior: auto !important;
}
.lenis.lenis-smooth [data-lenis-prevent] {
  overscroll-behavior: contain;
}
.lenis.lenis-stopped {
  overflow: hidden;
}
.lenis.lenis-scrolling iframe {
  pointer-events: none;
}
</style>
