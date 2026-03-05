import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

createApp(App).mount('#app')
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import './style.css'

createApp(App)
  .use(ElementPlus)
  .mount('#app')
