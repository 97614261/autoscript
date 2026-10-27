export const learningPaths = [
  { number: '01', label: '第一次使用', title: '跑通第一个项目', description: '认识项目、插件和积木，从一条提示开始验证流程。', link: '/guide/quick-start', action: '快速入门', icon: 'M5 4h10a3 3 0 0 1 3 3v13H7a3 3 0 0 1-3-3V5a1 1 0 0 1 1-1Zm-1 12h14M8 8h6M8 11h4' },
  { number: '02', label: '解决实际问题', title: '让画面成为输入', description: '从截图、找图到识字，了解选区、阈值和识别结果。', link: '/guide/vision', action: '图像与文字', icon: 'M8 3H4v4m12-4h4v4M4 16v4h4m12-4v4h-4M7 12h10M12 7v10' },
  { number: '03', label: '查阅与进阶', title: '找到需要的接口', description: '按名称或能力查询，核对参数、返回值和资源约定。', link: '/api/', action: 'API 参考', icon: 'm8 7-5 5 5 5m8-10 5 5-5 5M14 4l-4 16' }
]

export const recipes = [
  { id: 'vision', label: '找图点击', title: '看见目标，再执行操作', description: '把画面、判断和输入串成一条清晰的流程。', link: '/guide/vision', action: '了解识图流程', steps: [
    { title: '获取画面', detail: '截取当前屏幕', tag: 'Screen.capture', tone: 'screen' },
    { title: '寻找目标', detail: '在指定区域匹配图片', tag: 'Screen.findImages', tone: 'vision' },
    { title: '判断结果', detail: '检查是否找到目标', tag: '如果 / 否则', tone: 'logic' },
    { title: '执行操作', detail: '根据识别结果配置点击', tag: 'Input.tap', tone: 'input' }
  ] },
  { id: 'logic', label: '流程编排', title: '把重复的事，组织起来', description: '用变量表达状态，用条件与循环连接逻辑。', link: '/guide/quick-start', action: '学习积木编排', steps: [
    { title: '声明变量', detail: '先确定数据类型与作用域', tag: '变量', tone: 'screen' },
    { title: '设置判断', detail: '按条件选择要执行的分支', tag: '判断', tone: 'logic' },
    { title: '配置循环', detail: '明确次数、条件或期限', tag: '循环', tone: 'vision' },
    { title: '组合流程', detail: '将独立功能组织为插件', tag: '插件', tone: 'input' }
  ] },
  { id: 'debug', label: '单步调试', title: '先确认一步，再跑全程', description: '结合单步、执行高亮和日志定位问题。', link: '/guide/debugging', action: '查看调试指南', steps: [
    { title: '选择积木', detail: '确定要测试的节点与子树', tag: '选中', tone: 'screen' },
    { title: '执行单步', detail: '空闲时测试选中的操作', tag: '单步', tone: 'input' },
    { title: '观察位置', detail: '运行高亮跟随实际执行', tag: '运行高亮', tone: 'vision' },
    { title: '检查输出', detail: '区分脚本日志和诊断记录', tag: '日志', tone: 'logic' }
  ] }
]

export const commonModules = ['Screen', 'Input', 'Ocr', 'UI', 'Data', 'Task']
export const popularQueries = ['截图', '找图', 'Input.tap', '文字识别']

export function apiSearchLink(value = '') {
  const query = value.normalize('NFKC').trim().slice(0, 200)
  return query ? `/api/?${new URLSearchParams({ q: query })}` : '/api/'
}

export function apiModuleLink(module) {
  return `/api/?${new URLSearchParams({ module })}`
}
