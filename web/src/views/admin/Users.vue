<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">04 / USERS · 用户</span>
        <h2>用户管理</h2>
      </div>
      <el-button type="primary" @click="showAddUser">
        <el-icon><Plus /></el-icon> 添加用户
      </el-button>
    </div>

    <div class="admin-card">
      <!-- 筛选栏 -->
      <div class="filter-bar">
        <el-input v-model="filterUsername" placeholder="用户名模糊查询" clearable style="width:200px" @input="userPage = 1" />
        <el-button @click="filterUsername = ''; userPage = 1">重置</el-button>
      </div>

      <!-- 用户表格：列宽按内容自适应（上限 50 汉字），border 模式支持拖拽表头调宽，超宽时横向滚动 -->
      <el-table :data="pagedUsers" v-loading="loading" stripe border style="width:100%">
        <el-table-column prop="username" label="用户名" :width="colW.username" show-overflow-tooltip />
        <el-table-column label="角色" :width="colW.role" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="row.role === 'admin' ? 'status-enabled' : 'vis-admin'">
              {{ row.role === 'admin' ? '管理员' : '普通用户' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" :width="colW.status" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="row.disabled ? 'status-disabled' : 'status-enabled'">
              {{ row.disabled ? '已禁用' : '正常' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="每日限额" :width="colW.limit" align="center">
          <template #default="{ row }">
            <span v-if="row.dailyLimitType === 'count'" style="font-size:12px;">{{ row.dailyLimitValue }} 次/日</span>
            <span v-else-if="row.dailyLimitType === 'token'" style="font-size:12px;">{{ row.dailyLimitValue }} Token/日</span>
            <span v-else style="font-size:12px;color:var(--ink-3);">全局配额</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="注册时间" :width="colW.createdAt" show-overflow-tooltip />
        <el-table-column prop="lastLoginAt" label="最近登录" :width="colW.lastLoginAt" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastLoginAt || '-' }}</template>
        </el-table-column>
        <el-table-column prop="lastLoginIp" label="登录IP" :width="colW.lastLoginIp" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastLoginIp || '-' }}</template>
        </el-table-column>
        <el-table-column prop="lastLoginBrowser" label="浏览器" :width="colW.browser" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastLoginBrowser || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button size="small" text @click="editUser(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
              <el-button v-if="row.username !== 'admin'" size="small" text type="danger" @click="handleDelete(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
              <el-button v-if="row.role !== 'admin'" size="small" text @click="showPerms(row)">
                <el-icon><Key /></el-icon>
              </el-button>
              <el-button
                v-if="row.username !== 'admin'"
                size="small" text
                :type="row.disabled ? 'danger' : 'success'"
                :title="row.disabled ? '已禁用，点击启用账号' : '正常，点击禁用账号'"
                @click="handleToggleDisabled(row)"
              >
                <!-- icon 展示当前状态：正常=绿色解锁，禁用=红色锁定 -->
                <el-icon><component :is="row.disabled ? 'Lock' : 'Unlock'" /></el-icon>
              </el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
      <div class="admin-pager">
        <select v-model.number="userPageSize" class="admin-pager-size" @change="userPage = 1">
          <option :value="10">10条/页</option>
          <option :value="20">20条/页</option>
          <option :value="50">50条/页</option>
        </select>
        <button class="admin-pager-btn" :disabled="userPage <= 1" @click="userPage--">上一页</button>
        <span class="admin-pager-info">第 {{ userPage }} / {{ userTotalPages }} 页 · 共 {{ filteredUsers.length }} 条</span>
        <button class="admin-pager-btn" :disabled="userPage >= userTotalPages" @click="userPage++">下一页</button>
      </div>
    </div>

    <!-- 添加用户弹窗 -->
    <el-dialog v-model="addVisible" title="添加用户" width="420px" destroy-on-close>
      <el-form label-width="70px">
        <el-form-item label="用户名">
          <el-input v-model="addForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="addForm.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="addForm.role" style="width:100%">
            <el-option label="普通用户" value="user" />
            <el-option label="管理员" value="admin" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAddUser" :loading="submitting">添加</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户弹窗 -->
    <el-dialog v-model="editVisible" :title="'编辑用户 - ' + editForm.username" width="420px" destroy-on-close>
      <el-form label-width="70px">
        <el-form-item label="用户名">
          <el-input :model-value="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="editForm.password" type="password" placeholder="不修改则留空" show-password />
        </el-form-item>
        <el-form-item label="角色">
          <template v-if="editForm.username === 'admin'">
            <span style="color:#818cf8;font-weight:500;">管理员（内置用户不可修改）</span>
          </template>
          <template v-else>
            <el-select v-model="editForm.role" style="width:100%">
              <el-option label="普通用户" value="user" />
              <el-option label="管理员" value="admin" />
            </el-select>
          </template>
        </el-form-item>
        <el-form-item label="每日限额">
          <el-select v-model="editForm.dailyLimitType" style="width:100%;" @change="onLimitTypeChange">
            <el-option label="不单独限制" value="" />
            <el-option label="每日次数" value="count" />
            <el-option label="每日Token量" value="token" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editForm.dailyLimitType" label="参数设置">
          <div style="display:flex;align-items:center;gap:8px;width:100%;">
            <el-input-number
              v-model="editForm.dailyLimitValue"
              :min="1"
              :max="editForm.dailyLimitType === 'token' ? 100000000 : 100000"
              :step="editForm.dailyLimitType === 'token' ? 1000 : 10"
              style="flex:1;"
            />
            <span style="flex-shrink:0;color:var(--ink-2);font-size:13px;">{{ editForm.dailyLimitType === 'token' ? 'Token/日' : '次/日' }}</span>
          </div>
        </el-form-item>
        <el-form-item v-if="editForm.username !== 'admin'" label=" ">
          <span style="font-size:12px;color:var(--ink-3);line-height:1.6;">二选其一：按每日调用次数或每日 Token 总量限制。选“不单独限制”则回退全局配额。仅对普通用户生效。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveUser" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>

    <!-- 权限弹窗 -->
    <el-dialog v-model="permsVisible" :title="'用户权限 - ' + permsUsername" width="520px" destroy-on-close>
      <div style="margin-bottom:12px;color:var(--ink-2);font-size:13px;">勾选后该用户将<b>仅能使用</b>勾选的模型；全部不勾选表示不限制（可用所有公开模型）：</div>
      <div style="display:flex;flex-direction:column;gap:8px;max-height:360px;overflow-y:auto;">
        <el-checkbox
          v-for="m in allModels"
          :key="m.id"
          v-model="permsSelected[m.id]"
        >
          <span style="display:inline-flex;align-items:center;gap:6px;">
            <img v-if="getModelIcon(m)" :src="getModelIcon(m)" style="width:18px;height:18px;border-radius:3px;" />
            <span v-else-if="m.providerIcon" style="font-size:14px;">{{ m.providerIcon }}</span>
            {{ m.displayName || m.modelId }}
          </span>
        </el-checkbox>
      </div>
      <template #footer>
        <el-button @click="permsVisible = false">取消</el-button>
        <el-button type="primary" @click="savePermissions" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Key, Lock, Unlock } from '@element-plus/icons-vue'
import { getUsers, addUser, updateUser, deleteUser, updateUserPermissions } from '@/api/users'
import { getModels } from '@/api/models'
import { autoColWidth } from '@/composables/useTableAutoWidth'

const providerIconMap = {
  deepseek: '/icons/deepseek-icon.svg',
  qwen: '/icons/qwen-icon.svg',
  kimi: '/icons/kimi-icon.svg',
  zhipu: '/icons/zhipu-icon.svg',
  minimax: '/icons/minimax-icon.svg',
  doubao: '/icons/doubao-icon.svg'
}

const loading = ref(false)
const submitting = ref(false)
const allUsers = ref([])
const allModels = ref([])
const filterUsername = ref('')

const userPage = ref(1)
const userPageSize = ref(10)

const filteredUsers = computed(() => {
  if (!filterUsername.value) return allUsers.value
  const kw = filterUsername.value.toLowerCase()
  return allUsers.value.filter(u => (u.username || '').toLowerCase().includes(kw))
})

const pagedUsers = computed(() => {
  const start = (userPage.value - 1) * userPageSize.value
  return filteredUsers.value.slice(start, start + userPageSize.value)
})
const userTotalPages = computed(() => Math.max(1, Math.ceil(filteredUsers.value.length / userPageSize.value)))

// 列宽自适应：按当前列最长内容计算，上限 50 个汉字
// 每日限额列的展示文本
function limitText(u) {
  if (u.dailyLimitType === 'count') return `${u.dailyLimitValue} 次/日`
  if (u.dailyLimitType === 'token') return `${u.dailyLimitValue} Token/日`
  return '全局配额'
}
const colW = computed(() => {
  const list = allUsers.value
  return {
    username: autoColWidth(list.map(u => u.username), { header: '用户名', min: 100 }),
    role: autoColWidth(['管理员', '普通用户'], { header: '角色', extra: 24 }),
    status: autoColWidth(['已禁用', '正常'], { header: '状态', extra: 24 }),
    limit: autoColWidth(list.map(limitText), { header: '每日限额' }),
    createdAt: autoColWidth(list.map(u => u.createdAt), { header: '注册时间' }),
    lastLoginAt: autoColWidth(list.map(u => u.lastLoginAt || '-'), { header: '最近登录' }),
    lastLoginIp: autoColWidth(list.map(u => u.lastLoginIp || '-'), { header: '登录IP' }),
    browser: autoColWidth(list.map(u => u.lastLoginBrowser || '-'), { header: '浏览器' })
  }
})

function getModelIcon(m) {
  if (m.providerId && providerIconMap[m.providerId]) return providerIconMap[m.providerId]
  return null
}

// 添加用户
const addVisible = ref(false)
const addForm = ref({ username: '', password: '', role: 'user' })

function showAddUser() {
  addForm.value = { username: '', password: '', role: 'user' }
  addVisible.value = true
}

async function submitAddUser() {
  if (!addForm.value.username.trim() || !addForm.value.password.trim()) {
    ElMessage.warning('请填写用户名和密码')
    return
  }
  submitting.value = true
  try {
    const res = await addUser(addForm.value)
    if (res?.success) {
      ElMessage.success('添加成功')
      addVisible.value = false
      await loadUsers()
    } else {
      ElMessage.error(res?.message || '添加失败')
    }
  } finally {
    submitting.value = false
  }
}

// 编辑用户
const editVisible = ref(false)
const editForm = ref({ id: '', username: '', password: '', role: 'user' })

function editUser(row) {
  editForm.value = {
    id: row.id,
    username: row.username,
    password: '',
    role: row.role,
    dailyLimitType: row.dailyLimitType || '',
    dailyLimitValue: row.dailyLimitValue || 0
  }
  editVisible.value = true
}

// 切换限额类型：切换时给个合理默认值，清除时置 0
function onLimitTypeChange(type) {
  if (!type) {
    editForm.value.dailyLimitValue = 0
  } else if (!editForm.value.dailyLimitValue || editForm.value.dailyLimitValue <= 0) {
    editForm.value.dailyLimitValue = type === 'token' ? 100000 : 100
  }
}

async function saveUser() {
  submitting.value = true
  try {
    const payload = { role: editForm.value.role }
    if (editForm.value.password.trim()) payload.password = editForm.value.password.trim()
    // 每日限额（二选其一）：未选类型则传空以清除个人限额
    if (editForm.value.dailyLimitType) {
      payload.dailyLimitType = editForm.value.dailyLimitType
      payload.dailyLimitValue = editForm.value.dailyLimitValue || 0
    } else {
      payload.dailyLimitType = ''
      payload.dailyLimitValue = 0
    }
    const res = await updateUser(editForm.value.id, payload)
    if (res?.success) {
      ElMessage.success('保存成功')
      editVisible.value = false
      await loadUsers()
    } else {
      ElMessage.error(res?.message || '保存失败')
    }
  } finally {
    submitting.value = false
  }
}

// 禁用/启用账号：禁用后用户无法登录且已有登录态立即失效
async function handleToggleDisabled(row) {
  const action = row.disabled ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(
      row.disabled
        ? `确定启用用户 "${row.username}" 吗？启用后可正常登录。`
        : `确定禁用用户 "${row.username}" 吗？禁用后无法登录，已登录会话立即失效。`,
      `确认${action}`,
      { type: 'warning' }
    )
    const res = await updateUser(row.id, { disabled: !row.disabled })
    if (res?.success) {
      ElMessage.success(`已${action}`)
      await loadUsers()
    } else {
      ElMessage.error(res?.message || `${action}失败`)
    }
  } catch { /* cancelled */ }
}

// 删除用户
async function handleDelete(row) {
  if (row.username === 'admin') {
    ElMessage.warning('内置管理员账号不可删除')
    return
  }
  try {
    await ElMessageBox.confirm(`确定删除用户 "${row.username}" 吗？`, '确认删除', { type: 'warning' })
    const res = await deleteUser(row.id)
    if (res?.success) {
      ElMessage.success('已删除')
      await loadUsers()
    } else {
      ElMessage.error(res?.message || '删除失败')
    }
  } catch { /* cancelled */ }
}

// 权限管理
const permsVisible = ref(false)
const permsUsername = ref('')
const permsUserId = ref('')
const permsSelected = ref({})

function showPerms(row) {
  permsUsername.value = row.username
  permsUserId.value = row.id
  const allowed = row.allowedModelIds || []
  permsSelected.value = {}
  allModels.value.forEach(m => {
    permsSelected.value[m.id] = allowed.includes(m.id)
  })
  permsVisible.value = true
}

async function savePermissions() {
  const ids = Object.keys(permsSelected.value).filter(id => permsSelected.value[id])
  submitting.value = true
  try {
    const res = await updateUserPermissions(permsUserId.value, ids)
    if (res?.success) {
      ElMessage.success('权限已更新')
      permsVisible.value = false
      await loadUsers()
    } else {
      ElMessage.error(res?.message || '更新失败')
    }
  } finally {
    submitting.value = false
  }
}

async function loadUsers() {
  loading.value = true
  try {
    const res = await getUsers()
    if (res?.success) allUsers.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadModels() {
  const res = await getModels()
  if (res?.success) allModels.value = res.data || []
}

onMounted(() => {
  loadUsers()
  loadModels()
})
</script>
