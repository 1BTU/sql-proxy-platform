export interface User {
  username: string
  mustChangePassword: boolean
}

export interface ProxyConfig {
  id: number
  name: string
  dbType: string
  oracleConnectionMode?: 'SERVICE_NAME' | 'SID' | null
  host: string
  port: number
  database: string
  schema?: string
  username: string
  hasPassword: boolean
  maxRows: number
  queryTimeoutSeconds: number
  status: 'DRAFT' | 'TESTED' | 'PUBLISHED'
  configVersion: number
  testedVersion?: number
  lastTestMessage?: string
  lastTestAt?: string
  tokenCount: number
  createdAt: string
  updatedAt: string
}

export interface TokenResult {
  id: number
  remark: string
  token: string
  proxy: ProxyConfig
}

export interface TokenInfo {
  id: number
  proxyId: number
  remark: string
  createdAt: string
  updatedAt: string
}

export interface ProxyFormValue {
  name: string
  dbType: string
  oracleConnectionMode?: 'SERVICE_NAME' | 'SID' | null
  host: string
  port: number
  database: string
  schema?: string
  username: string
  password?: string
  maxRows: number
  queryTimeoutSeconds: number
}
