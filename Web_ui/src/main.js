import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'

// 应用入口只组装全局状态、路由和根组件，具体业务状态由页面和 Store 管理。
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
