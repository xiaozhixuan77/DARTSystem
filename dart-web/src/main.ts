import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import '@/assets/styles/reset.scss'
// highlight.js 浅色 GitHub 高亮主题（useMarkdown 生成 .hljs span 的配色来源）
import 'highlight.js/styles/github.css'
import '@/assets/styles/markdown.scss'
// 函数式 API（ElMessage/ElMessageBox）不在模板中，ElementPlusResolver 不会自动引入其样式
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
