/** 生成消息版本标识。 */
function generateVersionId() {
  return 'v' + Date.now().toString(36) + Math.random().toString(36).slice(2, 9)
}

/**
 * 将旧版 assistant 平铺字段无损升级为版本数组。
 */
export function ensureMessageVersions(message) {
  if (message.role !== 'assistant') return null
  if (!Array.isArray(message.versions) || message.versions.length === 0) {
    message.versions = [{
      versionId: message.currentVersionId || generateVersionId(),
      content: message.content || '',
      reasoning_content: message.reasoning_content,
      thinkingTime: message.thinkingTime,
      modelName: message.modelName,
      time: message.time,
      status: message.status || (message.interrupted ? 'stopped' : 'done'),
      isError: message.isError || undefined,
      usage: (message.promptTokens || message.completionTokens) ? {
        promptTokens: message.promptTokens || 0,
        completionTokens: message.completionTokens || 0,
        reasoningTokens: message.reasoningTokens || 0,
        cachedTokens: message.cachedTokens || 0
      } : undefined
    }]
    message.currentVersionId = message.versions[0].versionId
  }
  return message.versions
}

/** 将消息平铺字段同步为指定版本，保持现有渲染与导出兼容。 */
export function applyMessageVersion(message, version) {
  message.content = version.content
  message.reasoning_content = version.reasoning_content
  message.thinkingTime = version.thinkingTime
  message.modelName = version.modelName
  message.time = version.time
  message.status = version.status
  message.isError = version.isError || undefined
  message.interrupted = (version.status && version.status !== 'done') || undefined
  message.promptTokens = version.usage?.promptTokens
  message.completionTokens = version.usage?.completionTokens
  message.reasoningTokens = version.usage?.reasoningTokens
  message.cachedTokens = version.usage?.cachedTokens
}

/** 为 assistant 消息追加回答版本，并按需切换为当前版本。 */
export function appendMessageVersion(message, versionData, makeCurrent) {
  const versions = ensureMessageVersions(message)
  if (!versions) return null
  const versionId = generateVersionId()
  const version = {
    versionId,
    content: versionData.content || '',
    reasoning_content: versionData.reasoning_content,
    thinkingTime: versionData.thinkingTime,
    modelName: versionData.modelName,
    time: versionData.time,
    status: versionData.status || 'done',
    isError: versionData.isError || undefined,
    usage: versionData.usage
  }
  versions.push(version)
  if (makeCurrent) {
    message.currentVersionId = versionId
    applyMessageVersion(message, version)
  }
  return versionId
}

/** 切换 assistant 消息的当前展示版本。 */
export function selectMessageVersion(message, versionId) {
  const versions = ensureMessageVersions(message)
  if (!versions) return false
  const target = versions.find(version => version.versionId === versionId)
  if (!target || message.currentVersionId === versionId) return false
  message.currentVersionId = versionId
  applyMessageVersion(message, target)
  return true
}

/** 返回消息版本切换器使用的轻量视图。 */
export function getMessageVersionView(message) {
  const versions = ensureMessageVersions(message)
  if (!versions) return null
  const currentIndex = versions.findIndex(version => version.versionId === message.currentVersionId)
  return {
    list: versions.map(version => ({
      versionId: version.versionId,
      time: version.time,
      modelName: version.modelName,
      status: version.status
    })),
    currentIndex: currentIndex < 0 ? 0 : currentIndex,
    total: versions.length
  }
}
