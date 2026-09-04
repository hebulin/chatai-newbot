<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">04 / USERS · {{ $adminText('用户') }}</span>
        <h2>{{ $adminText('用户管理') }}</h2>
      </div>
      <div style="display:flex;gap:8px;">
        <el-button type="danger" :disabled="selectedRows.length === 0" @click="handleDeleteSelected">
          <el-icon><Delete /></el-icon> {{ $adminText('删除选中（{count}）', { count: selectedRows.length }) }}
        </el-button>
        <el-button type="primary" @click="showAddUser">
          <el-icon><Plus /></el-icon> {{ $adminText('添加用户') }}
        </el-button>
      </div>
    </div>

    <div class="admin-card">
      <!-- 筛选栏（服务端筛选） -->
      <div class="filter-bar">
        <el-input v-model="filterUsername" :placeholder="$adminText('用户名模糊查询')" clearable style="width:200px" @change="reloadUsers" />
        <el-button @click="filterUsername = ''; reloadUsers()">{{ $adminText('重置') }}</el-button>
      </div>

      <!-- 用户表格：列宽按内容自适应（上限 50 汉字），border 模式支持拖拽表头调宽，超宽时横向滚动；selection 列支持跨页勾选批量删除（内置 admin 不可勾选） -->
      <el-table ref="tableRef" :data="users" v-loading="loading" stripe border style="width:100%"
                @selection-change="onSelectionChange" row-key="id">
        <el-table-column type="selection" width="44" align="center" reserve-selection :selectable="isRowSelectable" />
        <el-table-column prop="username" :label="$adminText('用户名')" :width="colW.username" show-overflow-tooltip />
        <el-table-column prop="displayName" :label="$adminText('姓名')" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.displayName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="email" :label="$adminText('邮箱')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.email || '-' }}</template>
        </el-table-column>
        <el-table-column :label="$adminText('角色')" :width="colW.role" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="row.role === 'admin' ? 'status-enabled' : 'vis-admin'">
              {{ $adminText(row.role === 'admin' ? '管理员' : '普通用户') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="$adminText('状态')" :width="colW.status" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="row.disabled ? 'status-disabled' : 'status-enabled'">
              {{ $adminText(row.disabled ? '已禁用' : '正常') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="$adminText('每日限额')" :width="colW.limit" align="center">
          <template #default="{ row }">
            <span v-if="row.dailyLimitType === 'count'" style="font-size:12px;">{{ $adminText('{count} 次/日', { count: row.dailyLimitValue }) }}</span>
            <span v-else-if="row.dailyLimitType === 'token'" style="font-size:12px;">{{ $adminText('{count} Token/日', { count: row.dailyLimitValue }) }}</span>
            <span v-else style="font-size:12px;color:var(--ink-3);">{{ $adminText('全局配额') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="$adminText('注册时间')" :width="colW.createdAt" show-overflow-tooltip />
        <el-table-column prop="lastLoginAt" :label="$adminText('最近登录')" :width="colW.lastLoginAt" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastLoginAt || '-' }}</template>
        </el-table-column>
        <el-table-column prop="lastLoginIp" :label="$adminText('登录IP')" :width="colW.lastLoginIp" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastLoginIp || '-' }}</template>
        </el-table-column>
        <el-table-column prop="lastLoginBrowser" :label="$adminText('浏览器')" :width="colW.browser" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastLoginBrowser || '-' }}</template>
        </el-table-column>
        <el-table-column :label="$adminText('操作')" width="230" align="center" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button size="small" text @click="showUserDetail(row)">{{ $adminText('明细') }}</el-button>
              <el-button size="small" text :aria-label="$adminText('编辑用户')" @click="editUser(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
              <el-button v-if="row.username !== 'admin'" size="small" text type="danger" :aria-label="$adminText('删除用户')" @click="handleDelete(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
              <el-button v-if="row.role !== 'admin'" size="small" text :aria-label="$adminText('设置用户模型权限')" @click="showPerms(row)">
                <el-icon><Key /></el-icon>
              </el-button>
              <el-button
                v-if="row.username !== 'admin'"
                size="small" text
                :type="row.disabled ? 'danger' : 'success'"
                :title="$adminText(row.disabled ? '已禁用，点击启用账号' : '正常，点击禁用账号')"
                :aria-label="$adminText(row.disabled ? '启用用户' : '禁用用户')"
                @click="handleToggleDisabled(row)"
              >
                <!-- icon 展示当前状态：正常=绿色解锁，禁用=红色锁定 -->
                <el-icon><Lock v-if="row.disabled" /><Unlock v-else /></el-icon>
              </el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
      <!-- 统一分页组件（服务端分页：切换条数/翻页均回到加载逻辑） -->
      <AdminPager v-model:page="userPage" v-model:page-size="userPageSize"
        :total="userTotal" :total-pages="userTotalPages"
        @size-change="reloadUsers" @page-change="loadUsers" />
    </div>

    <!-- 用户明细弹窗 -->
    <el-dialog v-model="detailVisible" :title="$adminText('用户明细 - {name}', { name: detailUser.username })" width="760px" destroy-on-close>
      <div class="user-detail-header">
        <div class="admin-avatar-preview" :aria-label="$adminText('用户头像预览')">
          <img :src="avatarSource(detailUser)" :alt="$adminText('用户头像')" />
        </div>
        <div>
          <div class="user-detail-name">{{ detailUser.displayName || detailUser.username || '-' }}</div>
          <div class="user-detail-subtitle">{{ detailUser.username || '-' }}</div>
        </div>
      </div>
      <el-descriptions :column="2" border>
        <el-descriptions-item :label="$adminText('用户 ID')" :span="2">{{ detailUser.id || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('角色')">{{ $adminText(detailUser.role === 'admin' ? '管理员' : '普通用户') }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('状态')">{{ $adminText(detailUser.disabled ? '已禁用' : '正常') }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('邮箱')">{{ detailUser.email || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('联系电话')">{{ detailUser.phone || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('每日限额')">{{ limitText(detailUser) }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('注册时间')">{{ detailUser.createdAt || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('最近登录')">{{ detailUser.lastLoginAt || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('登录 IP')">{{ detailUser.lastLoginIp || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('浏览器')" :span="2">{{ detailUser.lastLoginBrowser || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('个人简介')" :span="2">{{ detailUser.bio || '-' }}</el-descriptions-item>
        <el-descriptions-item :label="$adminText('可用模型')" :span="2">
          <div v-if="detailAllowedModels.length" class="detail-model-tags">
            <el-tag v-for="model in detailAllowedModels" :key="model.id" size="small">{{ model.name }}</el-tag>
          </div>
          <span v-else>{{ $adminText('不限制（可用所有公开模型）') }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <template #footer><el-button @click="detailVisible = false">{{ $adminText('关闭') }}</el-button></template>
    </el-dialog>

    <!-- 添加用户弹窗 -->
    <el-dialog v-model="addVisible" :title="$adminText('添加用户')" width="420px" destroy-on-close>
      <el-form label-width="70px">
        <el-form-item :label="$adminText('用户名')">
          <el-input v-model="addForm.username" :placeholder="$adminText('请输入用户名')" />
        </el-form-item>
        <el-form-item :label="$adminText('密码')">
          <el-input v-model="addForm.password" type="password" :placeholder="$adminText('请输入密码')" show-password />
        </el-form-item>
        <el-form-item :label="$adminText('角色')">
          <el-select v-model="addForm.role" style="width:100%">
            <el-option :label="$adminText('普通用户')" value="user" />
            <el-option :label="$adminText('管理员')" value="admin" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">{{ $adminText('取消') }}</el-button>
        <el-button type="primary" @click="submitAddUser" :loading="submitting">{{ $adminText('添加') }}</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户弹窗 -->
    <el-dialog v-model="editVisible" :title="$adminText('编辑用户 - {name}', { name: editForm.username })" width="720px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item :label="$adminText('用户名')">
          <el-input :model-value="editForm.username" disabled />
        </el-form-item>
        <el-form-item :label="$adminText('新密码')">
          <el-input v-model="editForm.password" type="password" :placeholder="$adminText('不修改则留空')" show-password />
        </el-form-item>
        <el-form-item :label="$adminText('角色')">
          <template v-if="editForm.username === 'admin'">
            <span style="color:#818cf8;font-weight:500;">{{ $adminText('管理员（内置用户不可修改）') }}</span>
          </template>
          <template v-else>
            <el-select v-model="editForm.role" style="width:100%">
              <el-option :label="$adminText('普通用户')" value="user" />
              <el-option :label="$adminText('管理员')" value="admin" />
            </el-select>
          </template>
        </el-form-item>
        <el-form-item :label="$adminText('每日限额')">
          <el-select v-model="editForm.dailyLimitType" style="width:100%;" @change="onLimitTypeChange">
            <el-option :label="$adminText('不单独限制')" value="" />
            <el-option :label="$adminText('每日次数')" value="count" />
            <el-option :label="$adminText('每日Token量')" value="token" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editForm.dailyLimitType" :label="$adminText('参数设置')">
          <div style="display:flex;align-items:center;gap:8px;width:100%;">
            <el-input-number
              v-model="editForm.dailyLimitValue"
              :min="1"
              :max="editForm.dailyLimitType === 'token' ? 100000000 : 100000"
              :step="editForm.dailyLimitType === 'token' ? 1000 : 10"
              style="flex:1;"
            />
            <span style="flex-shrink:0;color:var(--ink-2);font-size:13px;">{{ $adminText(editForm.dailyLimitType === 'token' ? 'Token/日' : '次/日') }}</span>
          </div>
        </el-form-item>
        <el-form-item v-if="editForm.username !== 'admin'" label=" ">
          <span style="font-size:12px;color:var(--ink-3);line-height:1.6;">{{ $adminText('二选其一：按每日调用次数或每日 Token 总量限制。选“不单独限制”则回退全局配额。仅对普通用户生效。') }}</span>
        </el-form-item>
        <el-divider content-position="left">{{ $adminText('用户资料') }}</el-divider>
        <div class="avatar-edit-row">
          <div class="admin-avatar-preview" :aria-label="$adminText('编辑头像预览')">
            <img :src="avatarSource(editForm)" :alt="$adminText('头像预览')" />
          </div>
          <span>{{ $adminText('头像预览会随头像类型和 SVG 代码实时更新') }}</span>
        </div>
        <div class="user-profile-grid">
          <el-form-item :label="$adminText('姓名')"><el-input v-model="editForm.displayName" maxlength="80" /></el-form-item>
          <el-form-item :label="$adminText('邮箱')"><el-input v-model="editForm.email" maxlength="160" /></el-form-item>
          <el-form-item :label="$adminText('联系电话')"><el-input v-model="editForm.phone" maxlength="40" /></el-form-item>
          <el-form-item :label="$adminText('头像类型')">
            <el-select v-model="editForm.avatarType" style="width:100%"><el-option :label="$adminText('默认头像')" value="default" /><el-option :label="$adminText('SVG 代码')" value="svg" /></el-select>
          </el-form-item>
        </div>
        <el-form-item :label="$adminText('个人简介')"><el-input v-model="editForm.bio" type="textarea" :rows="3" maxlength="500" /></el-form-item>
        <el-form-item v-if="editForm.avatarType === 'svg'" :label="$adminText('SVG 代码')"><el-input v-model="editForm.avatarValue" type="textarea" :rows="5" maxlength="20000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ $adminText('取消') }}</el-button>
        <el-button type="primary" @click="saveUser" :loading="submitting">{{ $adminText('保存') }}</el-button>
      </template>
    </el-dialog>

    <!-- 权限弹窗 -->
    <el-dialog v-model="permsVisible" :title="$adminText('用户权限 - {name}', { name: permsUsername })" width="520px" destroy-on-close>
      <div style="margin-bottom:12px;color:var(--ink-2);font-size:13px;">{{ $adminText('勾选后该用户将仅能使用勾选的模型；全部不勾选表示不限制（可用所有公开模型）：') }}</div>
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
        <el-button @click="permsVisible = false">{{ $adminText('取消') }}</el-button>
        <el-button type="primary" @click="savePermissions" :loading="submitting">{{ $adminText('保存') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Key, Lock, Unlock } from '@element-plus/icons-vue'
import { getUsers, addUser, updateUser, deleteUser, batchDeleteUsers, updateUserPermissions } from '@/api/users'
import { getModels } from '@/api/models'
import { autoColWidth } from '@/composables/useTableAutoWidth'
import AdminPager from '@/components/admin/AdminPager.vue'
import { adminApiText, adminText } from '@/i18n'

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
const users = ref([])
const allModels = ref([])
const filterUsername = ref('')
const tableRef = ref(null)
const selectedRows = ref([])

const userPage = ref(1)
const userPageSize = ref(10)
const userTotal = ref(0)
const userTotalPages = ref(1)

// ===== 用户明细 =====
const detailVisible = ref(false)
const detailUser = ref({ username: '', allowedModelIds: [] })

// 将用户授权模型 ID 转换为明细弹窗中可读的模型名称
const detailAllowedModels = computed(() => {
  return (detailUser.value.allowedModelIds || []).map(id => {
    const model = allModels.value.find(item => item.id === id)
    return { id, name: model?.displayName || model?.modelId || id }
  })
})

// 列宽自适应：按当页列最长内容计算，上限 50 个汉字
// 每日限额列的展示文本
function limitText(u) {
  if (u.dailyLimitType === 'count') return adminText('{count} 次/日', { count: u.dailyLimitValue })
  if (u.dailyLimitType === 'token') return adminText('{count} Token/日', { count: u.dailyLimitValue })
  return adminText('全局配额')
}
const colW = computed(() => {
  const list = users.value
  return {
    username: autoColWidth(list.map(u => u.username), { header: adminText('用户名'), min: 100 }),
    role: autoColWidth(['管理员', '普通用户'].map(adminText), { header: adminText('角色'), extra: 24 }),
    status: autoColWidth(['已禁用', '正常'].map(adminText), { header: adminText('状态'), extra: 24 }),
    limit: autoColWidth(list.map(limitText), { header: adminText('每日限额') }),
    createdAt: autoColWidth(list.map(u => u.createdAt), { header: adminText('注册时间') }),
    lastLoginAt: autoColWidth(list.map(u => u.lastLoginAt || '-'), { header: adminText('最近登录') }),
    lastLoginIp: autoColWidth(list.map(u => u.lastLoginIp || '-'), { header: adminText('登录IP') }),
    browser: autoColWidth(list.map(u => u.lastLoginBrowser || '-'), { header: adminText('浏览器') })
  }
})

function getModelIcon(m) {
  if (m.providerId && providerIconMap[m.providerId]) return providerIconMap[m.providerId]
  return null
}

// 打开用户明细并复制当前行，避免列表刷新影响弹窗内容
function showUserDetail(row) {
  detailUser.value = { ...row, allowedModelIds: [...(row.allowedModelIds || [])] }
  detailVisible.value = true
}

// 返回用户头像地址；SVG 代码不完整时回退默认头像，防止预览出现破图
function avatarSource(user) {
  const svg = user?.avatarType === 'svg' ? String(user.avatarValue || '').trim() : ''
  return svg.toLocaleLowerCase().startsWith('<svg')
    ? 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
    : '/icons/user.svg'
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
    ElMessage.warning(adminText('请填写用户名和密码'))
    return
  }
  submitting.value = true
  try {
    const res = await addUser(addForm.value)
    if (res?.success) {
      ElMessage.success(adminText('添加成功'))
      addVisible.value = false
      await loadUsers()
    } else {
      ElMessage.error(adminApiText(res?.message, '添加失败'))
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
    dailyLimitValue: row.dailyLimitValue || 0,
    displayName: row.displayName || '',
    email: row.email || '',
    phone: row.phone || '',
    bio: row.bio || '',
    avatarType: row.avatarType || 'default',
    avatarValue: row.avatarValue || ''
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
    const payload = {
      role: editForm.value.role,
      displayName: editForm.value.displayName,
      email: editForm.value.email,
      phone: editForm.value.phone,
      bio: editForm.value.bio,
      avatarType: editForm.value.avatarType,
      avatarValue: editForm.value.avatarValue
    }
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
      ElMessage.success(adminText('保存成功'))
      editVisible.value = false
      await loadUsers()
    } else {
      ElMessage.error(adminApiText(res?.message, '保存失败'))
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
        ? adminText('确定启用用户 "{name}" 吗？启用后可正常登录。', { name: row.username })
        : adminText('确定禁用用户 "{name}" 吗？禁用后无法登录，已登录会话立即失效。', { name: row.username }),
      adminText('确认{action}', { action: adminText(action) }),
      { type: 'warning' }
    )
    const res = await updateUser(row.id, { disabled: !row.disabled })
    if (res?.success) {
      ElMessage.success(adminText('已{action}', { action: adminText(action) }))
      await loadUsers()
    } else {
      ElMessage.error(adminApiText(res?.message, '{action}失败', { action: adminText(action).toLowerCase() }))
    }
  } catch { /* cancelled */ }
}

// 删除用户
async function handleDelete(row) {
  if (row.username === 'admin') {
    ElMessage.warning(adminText('内置管理员账号不可删除'))
    return
  }
  try {
    await ElMessageBox.confirm(adminText('确定删除用户 "{name}" 吗？', { name: row.username }), adminText('确认删除'), { type: 'warning' })
    const res = await deleteUser(row.id)
    if (res?.success) {
      ElMessage.success(adminText('已删除'))
      await loadUsers()
    } else {
      ElMessage.error(adminApiText(res?.message, '删除失败'))
    }
  } catch { /* cancelled */ }
}

// 内置 admin 账号不可勾选（不可删除）
function isRowSelectable(row) {
  return row.username !== 'admin'
}

function onSelectionChange(rows) {
  selectedRows.value = rows
}

// 批量删除选中用户
async function handleDeleteSelected() {
  const rows = selectedRows.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(adminText('确定删除选中的 {count} 个用户吗？删除后不可恢复。', { count: rows.length }), adminText('确认批量删除'), { type: 'warning' })
    const res = await batchDeleteUsers(rows.map(r => r.id))
    if (res?.success) {
      ElMessage.success(adminText('已删除 {count} 个用户', { count: res.deleted ?? rows.length }))
      tableRef.value?.clearSelection()
      await loadUsers()
    } else {
      ElMessage.error(adminApiText(res?.message, '删除失败'))
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
      ElMessage.success(adminText('权限已更新'))
      permsVisible.value = false
      await loadUsers()
    } else {
      ElMessage.error(adminApiText(res?.message, '更新失败'))
    }
  } finally {
    submitting.value = false
  }
}

// 加载用户列表（服务端分页）；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用，避免闪烁）
async function loadUsers(silent = false) {
  if (!silent) loading.value = true
  try {
    const params = { page: userPage.value, size: userPageSize.value }
    if (filterUsername.value) params.username = filterUsername.value
    const res = await getUsers(params)
    if (res?.success) {
      users.value = res.data || []
      userTotal.value = res.total || 0
      userTotalPages.value = res.totalPages || 1
      userPage.value = res.page || 1
    }
  } finally {
    loading.value = false
  }
}

// 筛选/页大小变化：回到第一页重新加载
function reloadUsers() {
  userPage.value = 1
  loadUsers()
}

async function loadModels() {
  const res = await getModels()
  if (res?.success) allModels.value = res.data || []
}

onMounted(() => {
  loadUsers()
  loadModels()
})

// keep-alive 缓存下再次进入本页时静默刷新数据；
// 首次挂载由 onMounted 负责加载，跳过第一次激活避免重复请求
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  loadUsers(true)
  loadModels()
})
</script>

<style scoped>
.user-profile-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}
.avatar-edit-row,
.user-detail-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 18px;
  color: var(--ink-3);
  font-size: 12px;
}
.admin-avatar-preview {
  display: flex;
  width: 64px;
  height: 64px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 50%;
  background: var(--paper-2);
}
.admin-avatar-preview img { width: 100%; height: 100%; object-fit: cover; }
.user-detail-name { color: var(--ink-1); font-size: 18px; font-weight: 600; }
.user-detail-subtitle { margin-top: 4px; color: var(--ink-3); font-size: 12px; }
.detail-model-tags { display: flex; flex-wrap: wrap; gap: 6px; }
@media (max-width: 720px) {
  .user-profile-grid { grid-template-columns: 1fr; }
}
</style>
