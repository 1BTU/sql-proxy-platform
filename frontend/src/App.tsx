import { useEffect, useMemo, useState } from 'react'
import {
  ApiOutlined, CheckCircleOutlined, CopyOutlined, DatabaseOutlined, DeleteOutlined,
  EditOutlined, EyeOutlined, LogoutOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined,
} from '@ant-design/icons'
import {
  Alert, App as AntApp, Button, Card, Descriptions, Drawer, Empty, Form, Input, InputNumber,
  Layout, Modal, Popconfirm, Select, Space, Spin, Table, Tag, Typography,
} from 'antd'
import { api } from './api'
import type { ProxyConfig, ProxyFormValue, TokenInfo, TokenResult, User } from './types'

const { Header, Content } = Layout
const statusMap = {
  DRAFT: { color: 'default', text: '待测试' },
  TESTED: { color: 'processing', text: '已测试' },
  PUBLISHED: { color: 'success', text: '已发布' },
} as const

function Login({ onLogin }: { onLogin: (user: User) => void }) {
  const { message } = AntApp.useApp()
  const [loading, setLoading] = useState(false)
  const submit = async (values: { username: string; password: string }) => {
    setLoading(true)
    try {
      onLogin(await api<User>('/api/admin/auth/login', { method: 'POST', body: JSON.stringify(values) }))
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setLoading(false)
    }
  }
  return <div className="auth-page">
    <Card className="auth-card" bordered={false}>
      <div className="brand-icon"><SafetyCertificateOutlined /></div>
      <Typography.Title level={2}>数据库安全代理平台</Typography.Title>
      <Typography.Paragraph type="secondary">登录后配置、测试并发布安全的只读数据库代理</Typography.Paragraph>
      <Form layout="vertical" onFinish={submit} initialValues={{ username: 'admin' }} requiredMark={false}>
        <Form.Item label="管理员账号" name="username" rules={[{ required: true }]}><Input size="large" /></Form.Item>
        <Form.Item label="密码" name="password" rules={[{ required: true }]}><Input.Password size="large" /></Form.Item>
        <Button htmlType="submit" type="primary" size="large" block loading={loading}>登录</Button>
      </Form>
      <Alert className="auth-note" type="info" showIcon message="首次部署默认账号 admin，初始密码 Admin@123456，登录后必须修改。" />
    </Card>
  </div>
}

function ChangePassword({ onChanged }: { onChanged: () => void }) {
  const { message } = AntApp.useApp()
  const [loading, setLoading] = useState(false)
  const submit = async (values: { oldPassword: string; newPassword: string; confirm: string }) => {
    setLoading(true)
    try {
      await api('/api/admin/auth/change-password', {
        method: 'POST', body: JSON.stringify({ oldPassword: values.oldPassword, newPassword: values.newPassword }),
      })
      message.success('密码已修改')
      onChanged()
    } catch (error) {
      message.error((error as Error).message)
    } finally { setLoading(false) }
  }
  return <div className="auth-page">
    <Card className="auth-card" bordered={false}>
      <Typography.Title level={2}>修改初始密码</Typography.Title>
      <Alert type="warning" showIcon message="为保证平台安全，完成改密后才能配置数据库代理。" />
      <Form layout="vertical" onFinish={submit} requiredMark={false} className="password-form">
        <Form.Item label="当前密码" name="oldPassword" rules={[{ required: true }]}><Input.Password size="large" /></Form.Item>
        <Form.Item label="新密码" name="newPassword" rules={[{ required: true }, { min: 10, message: '至少 10 个字符' }]}><Input.Password size="large" /></Form.Item>
        <Form.Item label="确认新密码" name="confirm" dependencies={['newPassword']} rules={[
          { required: true }, ({ getFieldValue }) => ({ validator(_, value) { return value === getFieldValue('newPassword') ? Promise.resolve() : Promise.reject(new Error('两次密码不一致')) } }),
        ]}><Input.Password size="large" /></Form.Item>
        <Button htmlType="submit" type="primary" size="large" block loading={loading}>保存新密码</Button>
      </Form>
    </Card>
  </div>
}

function ProxyForm({ open, current, onClose, onSaved }: {
  open: boolean; current?: ProxyConfig; onClose: () => void; onSaved: () => void
}) {
  const { message } = AntApp.useApp()
  const [form] = Form.useForm<ProxyFormValue>()
  const [saving, setSaving] = useState(false)
  useEffect(() => {
    if (!open) return
    form.setFieldsValue(current ? { ...current, password: '', oracleConnectionMode: current.oracleConnectionMode || 'SERVICE_NAME' } : {
      dbType: 'mysql', port: 3306, maxRows: 1000, queryTimeoutSeconds: 30, oracleConnectionMode: 'SERVICE_NAME',
    })
  }, [open, current, form])
  const type = Form.useWatch('dbType', form)
  const oracleMode = Form.useWatch('oracleConnectionMode', form) || 'SERVICE_NAME'
  useEffect(() => {
    if (!current && type) form.setFieldValue('port', type === 'mysql' ? 3306 : type === 'postgresql' ? 5432 : type === 'oracle' ? 1521 : 5236)
  }, [type, current, form])
  const submit = async (values: ProxyFormValue) => {
    setSaving(true)
    try {
      await api(current ? `/api/admin/proxies/${current.id}` : '/api/admin/proxies', {
        method: current ? 'PUT' : 'POST', body: JSON.stringify(values),
      })
      message.success(current ? '代理已更新，需要重新测试' : '代理已创建')
      onSaved()
    } catch (error) { message.error((error as Error).message) } finally { setSaving(false) }
  }
  return <Drawer title={current ? '编辑数据库代理' : '新建数据库代理'} open={open} onClose={onClose} width={560} destroyOnClose>
    <Form form={form} layout="vertical" onFinish={submit} requiredMark={false}>
      <Form.Item label="代理名称" name="name" rules={[{ required: true }]}><Input placeholder="例如：业务报表只读库" /></Form.Item>
      <div className="form-grid">
        <Form.Item label="数据库类型" name="dbType" rules={[{ required: true }]}>
          <Select options={[{ value: 'mysql', label: 'MySQL' }, { value: 'postgresql', label: 'PostgreSQL' }, { value: 'dm', label: '达梦 DM' }, { value: 'oracle', label: 'Oracle（兼容 11g SQL）' }]} />
        </Form.Item>
        <Form.Item label="端口" name="port" rules={[{ required: true }]}><InputNumber min={1} max={65535} className="full-width" /></Form.Item>
      </div>
      <Form.Item label="主机地址" name="host" rules={[{ required: true }]}><Input placeholder="10.0.0.10" /></Form.Item>
      {type === 'oracle' && <Form.Item label="Oracle 连接模式" name="oracleConnectionMode" rules={[{ required: true }]}>
        <Select options={[{ value: 'SERVICE_NAME', label: 'Service Name（服务名）' }, { value: 'SID', label: 'SID（实例名）' }]} />
      </Form.Item>}
      <div className="form-grid">
        <Form.Item label={type === 'oracle' ? oracleMode === 'SID' ? 'SID（实例名）' : 'Service Name（服务名）' : '数据库'} name="database" rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item label="Schema（可选）" name="schema" extra={type === 'postgresql' ? 'PG：默认且允许直接查询的 Schema；留空不限制' : type === 'oracle' ? 'Oracle：默认且允许直接查询的 Schema；留空不限制。普通名称转大写，区分大小写时用双引号。' : undefined}><Input /></Form.Item>
      </div>
      <div className="form-grid">
        <Form.Item label="数据库账号" name="username" rules={[{ required: true }]}><Input autoComplete="off" /></Form.Item>
        <Form.Item label={current ? '数据库密码（留空保持原密码）' : '数据库密码'} name="password" rules={current ? [] : [{ required: true }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </div>
      <div className="form-grid">
        <Form.Item label="最大返回行数" name="maxRows" rules={[{ required: true }]}><InputNumber min={1} max={100000} className="full-width" /></Form.Item>
        <Form.Item label="查询超时（秒）" name="queryTimeoutSeconds" rules={[{ required: true }]}><InputNumber min={1} max={600} className="full-width" /></Form.Item>
      </div>
      <Alert type="info" showIcon message="请使用数据库侧只读账号；平台还会额外拦截写入、DDL、多语句和加锁查询。" />
      <div className="drawer-actions"><Button onClick={onClose}>取消</Button><Button type="primary" htmlType="submit" loading={saving}>保存</Button></div>
    </Form>
  </Drawer>
}

function TokenModal({ result, onClose, onRegenerate }: { result?: TokenResult; onClose: () => void; onRegenerate: () => void }) {
  const { message } = AntApp.useApp()
  const curl = useMemo(() => result ? [
    `curl -X POST "${window.location.origin}/api/v1/query" \\`,
    `  -H "Authorization: Bearer ${result.token}" \\`,
    '  -H "Content-Type: application/json" \\',
    `  -d '${JSON.stringify({ sql: result.proxy.dbType.toLowerCase() === 'oracle' ? 'SELECT 1 AS RESULT FROM DUAL' : 'SELECT 1', format: 'json' })}'`,
  ].join('\n') : '', [result])
  const copy = async (text: string, label: string) => { await navigator.clipboard.writeText(text); message.success(`${label}已复制`) }
  return <Modal title={`代理调用凭据${result?.remark ? ` · ${result.remark}` : ''}`} open={!!result} onCancel={onClose} footer={null} width={720}>
    {result && <Space direction="vertical" size="large" className="full-width">
      <Alert type="warning" showIcon message="Token 等同访问密码，请仅交给可信调用方，并通过 HTTPS 传输。" />
      <div><Typography.Text strong>Bearer Token</Typography.Text><Input.Password value={result.token} readOnly addonAfter={<CopyOutlined onClick={() => copy(result.token, 'Token')} />} /></div>
      <div><Typography.Text strong>curl 示例</Typography.Text><pre className="code-block">{curl}</pre><Button icon={<CopyOutlined />} onClick={() => copy(curl, 'curl 命令')}>复制 curl</Button></div>
      <Descriptions size="small" column={1} bordered items={[
        { key: 'url', label: 'Postman URL', children: `${window.location.origin}/api/v1/query` },
        { key: 'auth', label: 'Authorization', children: 'Type 选择 Bearer Token，并粘贴上方 Token' },
        { key: 'body', label: 'Body', children: <Space direction="vertical" size={4}>
          <div>选择 raw / JSON。sql 必填，提交完整的只读 SQL，不使用参数占位符。</div>
          <div>format 可选，默认 json；可填写以下四种值：</div>
          <div><code>json</code>：结构化 JSON，包含查询数据 data、列名 columns 等字段。</div>
          <div><code>csv</code>：逗号分隔文本，包含表头，可保存为 CSV 文件。</div>
          <div><code>markdown</code>：标准 Markdown 表格，仅包含表头和数据，不包含标题或总数。</div>
          <div><code>html</code>：HTML 表格页面，可在浏览器中显示。</div>
        </Space> },
      ]} />
      <Popconfirm title="重新生成后旧 Token 立即失效，确定继续？" onConfirm={onRegenerate}>
        <Button danger icon={<ReloadOutlined />}>重新生成 Token</Button>
      </Popconfirm>
    </Space>}
  </Modal>
}

function TokenManagerModal({ proxy, onClose, onReveal, onChanged }: {
  proxy?: ProxyConfig; onClose: () => void; onReveal: (token: TokenResult) => void; onChanged: () => void
}) {
  const { message } = AntApp.useApp()
  const [tokens, setTokens] = useState<TokenInfo[]>([])
  const [remark, setRemark] = useState('')
  const [editing, setEditing] = useState<TokenInfo>()
  const [editRemark, setEditRemark] = useState('')
  const load = async () => {
    if (!proxy) return
    try { setTokens(await api<TokenInfo[]>(`/api/admin/proxies/${proxy.id}/tokens`)) }
    catch (error) { message.error((error as Error).message) }
  }
  useEffect(() => { if (proxy) void load(); else setTokens([]) }, [proxy?.id])
  const create = async () => {
    if (!proxy) return
    try {
      const result = await api<TokenResult>(`/api/admin/proxies/${proxy.id}/tokens`, {
        method: 'POST', body: JSON.stringify({ remark }),
      })
      setRemark(''); onReveal(result); await load(); onChanged(); message.success('Token 已创建')
    } catch (error) { message.error((error as Error).message) }
  }
  const reveal = async (item: TokenInfo) => {
    if (!proxy) return
    try { onReveal(await api<TokenResult>(`/api/admin/proxies/${proxy.id}/tokens/${item.id}`)) }
    catch (error) { message.error((error as Error).message) }
  }
  const regenerate = async (item: TokenInfo) => {
    if (!proxy) return
    try {
      onReveal(await api<TokenResult>(`/api/admin/proxies/${proxy.id}/tokens/${item.id}/regenerate`, { method: 'POST' }))
      await load(); message.success('Token 已重新生成，旧 Token 立即失效')
    } catch (error) { message.error((error as Error).message) }
  }
  const revoke = async (item: TokenInfo) => {
    if (!proxy) return
    try {
      await api(`/api/admin/proxies/${proxy.id}/tokens/${item.id}`, { method: 'DELETE' })
      await load(); onChanged(); message.success('Token 已撤销')
    } catch (error) { message.error((error as Error).message) }
  }
  const saveRemark = async () => {
    if (!proxy || !editing) return
    try {
      await api(`/api/admin/proxies/${proxy.id}/tokens/${editing.id}`, {
        method: 'PUT', body: JSON.stringify({ remark: editRemark }),
      })
      setEditing(undefined); await load(); message.success('备注已更新')
    } catch (error) { message.error((error as Error).message) }
  }
  const columns = [
    { title: '备注', dataIndex: 'remark', render: (value: string) => value || <span className="muted">无备注</span> },
    { title: '创建时间', dataIndex: 'createdAt', render: (value: string) => new Date(value).toLocaleString() },
    { title: '操作', width: 285, render: (_: unknown, item: TokenInfo) => <Space wrap>
      <Button size="small" icon={<EyeOutlined />} onClick={() => reveal(item)}>查看</Button>
      <Button size="small" icon={<EditOutlined />} onClick={() => { setEditing(item); setEditRemark(item.remark) }}>备注</Button>
      <Popconfirm title="重新生成后旧 Token 立即失效，确定继续？" onConfirm={() => regenerate(item)}>
        <Button size="small" icon={<ReloadOutlined />} disabled={proxy?.status !== 'PUBLISHED'}>重新生成</Button>
      </Popconfirm>
      <Popconfirm title="撤销后该 Token 立即失效，确定继续？" onConfirm={() => revoke(item)}>
        <Button size="small" danger icon={<DeleteOutlined />} />
      </Popconfirm>
    </Space> },
  ]
  return <>
    <Modal title={`Token 管理 · ${proxy?.name || ''}`} open={!!proxy} onCancel={onClose} footer={null} width={860}>
      <Space.Compact block style={{ marginBottom: 16 }}>
        <Input value={remark} maxLength={200} onChange={event => setRemark(event.target.value)} placeholder="Token 备注，例如：报表系统、Postman 测试" />
        <Button type="primary" icon={<PlusOutlined />} onClick={create} disabled={proxy?.status !== 'PUBLISHED'}>新增 Token</Button>
      </Space.Compact>
      {proxy?.status !== 'PUBLISHED' && <Alert type="info" showIcon message="代理未发布，现有 Token 已暂停；重新发布后恢复使用。" style={{ marginBottom: 16 }} />}
      <Table rowKey="id" size="small" columns={columns} dataSource={tokens} pagination={false} locale={{ emptyText: '暂无 Token' }} />
    </Modal>
    <Modal title="修改 Token 备注" open={!!editing} onCancel={() => setEditing(undefined)} onOk={saveRemark}>
      <Input value={editRemark} maxLength={200} onChange={event => setEditRemark(event.target.value)} placeholder="请输入备注" />
    </Modal>
  </>
}

function Dashboard({ user, onLogout }: { user: User; onLogout: () => void }) {
  const { message } = AntApp.useApp()
  const [items, setItems] = useState<ProxyConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<ProxyConfig | undefined>()
  const [formOpen, setFormOpen] = useState(false)
  const [token, setToken] = useState<TokenResult>()
  const [tokenProxy, setTokenProxy] = useState<ProxyConfig>()
  const load = async () => {
    setLoading(true)
    try { setItems(await api<ProxyConfig[]>('/api/admin/proxies')) }
    catch (error) { message.error((error as Error).message) }
    finally { setLoading(false) }
  }
  useEffect(() => { void load() }, [])
  const act = async (path: string, success: string, showToken = false) => {
    try {
      const data = await api<ProxyConfig | TokenResult>(path, { method: 'POST' })
      if (showToken) setToken(data as TokenResult)
      message.success(success); await load()
    } catch (error) { message.error((error as Error).message) }
  }
  const remove = async (id: number) => {
    try { await api(`/api/admin/proxies/${id}`, { method: 'DELETE' }); message.success('代理已删除'); await load() }
    catch (error) { message.error((error as Error).message) }
  }
  const logout = async () => { try { await api('/api/admin/auth/logout', { method: 'POST' }) } finally { onLogout() } }
  const columns = [
    { title: '代理', dataIndex: 'name', render: (name: string, row: ProxyConfig) => <div><b>{name}</b><div className="muted">{row.dbType.toUpperCase()} · {row.host}:{row.port}</div></div> },
    { title: '数据库', dataIndex: 'database', render: (value: string, row: ProxyConfig) => <div>{value}<div className="muted">{row.username}</div></div> },
    { title: '限制', render: (_: unknown, row: ProxyConfig) => <span>{row.maxRows} 行 / {row.queryTimeoutSeconds} 秒</span> },
    { title: '状态', dataIndex: 'status', render: (value: ProxyConfig['status']) => <Tag color={statusMap[value].color}>{statusMap[value].text}</Tag> },
    { title: '操作', width: 370, render: (_: unknown, row: ProxyConfig) => <Space wrap>
      <Button size="small" icon={<EditOutlined />} onClick={() => { setEditing(row); setFormOpen(true) }}>编辑</Button>
      <Button size="small" icon={<CheckCircleOutlined />} onClick={() => act(`/api/admin/proxies/${row.id}/test`, '连接测试成功')}>测试</Button>
      {row.status === 'PUBLISHED' ? <>
        <Button size="small" icon={<EyeOutlined />} onClick={() => setTokenProxy(row)}>Token ({row.tokenCount})</Button>
        <Button size="small" onClick={() => act(`/api/admin/proxies/${row.id}/unpublish`, '代理已取消发布')}>取消发布</Button>
      </> : <><Button size="small" type="primary" disabled={row.status !== 'TESTED'} icon={<ApiOutlined />} onClick={() => act(`/api/admin/proxies/${row.id}/publish`, '代理发布成功', true)}>发布</Button>
        {row.tokenCount > 0 && <Button size="small" icon={<EyeOutlined />} onClick={() => setTokenProxy(row)}>Token ({row.tokenCount})</Button>}
      </>}
      <Popconfirm title="删除后 Token 将立即失效，确定删除？" onConfirm={() => remove(row.id)}><Button size="small" danger icon={<DeleteOutlined />} /></Popconfirm>
    </Space> },
  ]
  return <Layout className="app-layout">
    <Header className="app-header">
      <div className="header-brand"><DatabaseOutlined /><span>数据库安全代理平台</span></div>
      <Space><Typography.Text className="header-user">{user.username}</Typography.Text><Button type="text" icon={<LogoutOutlined />} onClick={logout}>退出</Button></Space>
    </Header>
    <Content className="content">
      <div className="page-title"><div><Typography.Title level={2}>数据库代理</Typography.Title><Typography.Text type="secondary">配置、验证并发布只读查询入口</Typography.Text></div><Button type="primary" size="large" icon={<PlusOutlined />} onClick={() => { setEditing(undefined); setFormOpen(true) }}>新建代理</Button></div>
      <Card bordered={false} className="table-card">
        <Spin spinning={loading}>
          {items.length ? <Table rowKey="id" columns={columns} dataSource={items} pagination={false} scroll={{ x: 1000 }} /> : <Empty description="尚未配置数据库代理"><Button type="primary" onClick={() => setFormOpen(true)}>创建第一个代理</Button></Empty>}
        </Spin>
      </Card>
    </Content>
    <ProxyForm open={formOpen} current={editing} onClose={() => setFormOpen(false)} onSaved={() => { setFormOpen(false); void load() }} />
    <TokenManagerModal proxy={tokenProxy} onClose={() => setTokenProxy(undefined)} onReveal={setToken} onChanged={() => void load()} />
    <TokenModal result={token} onClose={() => setToken(undefined)} onRegenerate={async () => {
      if (!token) return
      try {
        setToken(await api<TokenResult>(`/api/admin/proxies/${token.proxy.id}/tokens/${token.id}/regenerate`, { method: 'POST' }))
        message.success('Token 已重新生成')
      } catch (error) { message.error((error as Error).message) }
    }} />
  </Layout>
}

function Root() {
  const [user, setUser] = useState<User | null | undefined>(undefined)
  const refresh = async () => { try { setUser(await api<User>('/api/admin/auth/me')) } catch { setUser(null) } }
  useEffect(() => { void refresh() }, [])
  if (user === undefined) return <div className="center"><Spin size="large" /></div>
  if (!user) return <Login onLogin={setUser} />
  if (user.mustChangePassword) return <ChangePassword onChanged={refresh} />
  return <Dashboard user={user} onLogout={() => setUser(null)} />
}

export default function App() { return <AntApp><Root /></AntApp> }
