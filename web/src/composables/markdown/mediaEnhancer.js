/** 为 AI 回复中的图片和视频补充预览与下载操作。 */
export function enhanceGeneratedMedia(container) {
  enhanceImages(container)
  enhanceVideos(container)
}

/** 为 AI 图片增加灯箱入口和下载栏。 */
function enhanceImages(container) {
  container.querySelectorAll('.msg-bubble img:not(.user-msg-img):not([data-img-enhanced])').forEach(image => {
    image.setAttribute('data-img-enhanced', 'true')
    image.classList.add('ai-msg-img')
    image.style.cursor = 'pointer'
    const openLightbox = () => image.dispatchEvent(new CustomEvent('lightbox', { detail: { src: image.src }, bubbles: true }))
    image.addEventListener('click', openLightbox)
    const wrapper = document.createElement('div')
    wrapper.className = 'ai-image-wrapper'
    image.parentNode.insertBefore(wrapper, image)
    wrapper.appendChild(image)
    const toolbar = document.createElement('div')
    toolbar.className = 'ai-image-toolbar'
    toolbar.innerHTML = '<button class="ai-img-btn" title="放大预览"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg></button>' +
      '<button class="ai-img-btn" title="下载"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></button>'
    const [zoomButton, downloadButton] = toolbar.querySelectorAll('button')
    zoomButton.addEventListener('click', event => { event.stopPropagation(); openLightbox() })
    downloadButton.addEventListener('click', event => { event.stopPropagation(); downloadMedia(image.src, `ai-image-${Date.now()}`) })
    wrapper.appendChild(toolbar)
  })
}

/** 为 AI 视频开启原生控件并增加下载栏。 */
function enhanceVideos(container) {
  container.querySelectorAll('.msg-bubble video:not([data-video-enhanced])').forEach(video => {
    video.setAttribute('data-video-enhanced', 'true')
    video.setAttribute('controls', 'true')
    video.setAttribute('controlslist', 'nodownload')
    video.setAttribute('preload', 'metadata')
    const wrapper = document.createElement('div')
    wrapper.className = 'ai-video-wrapper'
    video.parentNode.insertBefore(wrapper, video)
    wrapper.appendChild(video)
    const toolbar = document.createElement('div')
    toolbar.className = 'ai-video-toolbar'
    toolbar.innerHTML = '<button class="ai-vid-btn" title="下载视频"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg> 下载</button>'
    toolbar.querySelector('button').addEventListener('click', event => { event.stopPropagation(); downloadMedia(video.src, `ai-video-${Date.now()}`) })
    wrapper.appendChild(toolbar)
  })
}

/** 下载远程媒体，跨域失败时回退为新窗口打开。 */
function downloadMedia(url, filename) {
  fetch(url).then(response => response.blob()).then(blob => {
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = filename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    setTimeout(() => URL.revokeObjectURL(link.href), 1000)
  }).catch(() => window.open(url, '_blank'))
}
