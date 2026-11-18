import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as Icons from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)

for (const [name, component] of Object.entries(Icons)) {
  app.component(name, component)
}

app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
