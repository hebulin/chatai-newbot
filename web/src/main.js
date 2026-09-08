import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 模板组件由 unplugin-vue-components 按需自动导入（含样式）；
// 此处仅引入命令式 API（ElMessage 等）与 v-loading 指令及它们的样式、暗黑主题变量
import { ElLoading } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-notification.css'
import 'element-plus/theme-chalk/el-loading.css'
// ElMessageBox 自定义渲染里用到 ElCheckbox（非模板使用，按需插件扫不到），样式需手动引入
import 'element-plus/theme-chalk/el-checkbox.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
// 仅注册项目实际用到的图标（含 Users 页 <component :is> 动态引用的 Lock/Unlock），
// 替代全量注册以减小打包体积；新增图标使用时需同步补充此处
import {
  ArrowUp, Back, Connection, CopyDocument, Delete, Edit, Grid,
  Key, Loading, Lock, Plus, Unlock, View
} from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
// 全站中英双语，locale 持久化在 localStorage；后台模板复用中文源文案映射函数。
import i18n, { adminText } from './i18n'
import './styles/variables.css'
import './theme/admin-theme.css'

const app = createApp(App)

// 按需注册 Element Plus 图标
const icons = { ArrowUp, Back, Connection, CopyDocument, Delete, Edit, Grid, Key, Loading, Lock, Plus, Unlock, View }
for (const [key, component] of Object.entries(icons)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(i18n)
// 后台模板统一调用 $adminText，切换 locale 后随响应式渲染自动刷新。
app.config.globalProperties.$adminText = adminText
// v-loading 指令非模板组件，按需插件无法自动注册，需显式安装
app.use(ElLoading)

app.mount('#app')
