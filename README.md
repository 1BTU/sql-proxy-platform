# 数据库安全代理平台

面向 MySQL、PostgreSQL、达梦和 Oracle 数据库的只读查询代理。管理员在网页中保存数据库连接、测试并发布；调用方只使用 Bearer Token，不接触数据库账号和密码。每个代理可创建多个带备注的 Token，并可分别查看、轮换或撤销。

## 本地开发

要求 Java 21、Maven 3.9+、Node.js 20+。

```bash
# 生成 32 字节 AES 主密钥
export APP_ENCRYPTION_KEY="$(openssl rand -base64 32 | tr -d '\n')"

cd frontend
npm ci
npm run build
cd ..

mkdir -p src/main/resources/static
cp -r frontend/dist/. src/main/resources/static/
mvn test package
java -jar target/dm-database-api-1.0.0.jar
```

使用部署脚本时默认访问 `http://localhost:3001`，默认账号为 `admin / Admin@123456`，首次登录必须修改密码。可通过 `ADMIN_INITIAL_USERNAME` 和 `ADMIN_INITIAL_PASSWORD` 覆盖首次初始化值。

## 外部查询接口

`v1.1.10` 修正离线镜像带入旧版前端的问题。页面的复制示例仅提交完整 SQL，不含 `params` 或问号参数占位符；增加实际打包静态资源的回归检查，构建时阻止旧示例再次进入镜像。

`v1.1.11` 在 curl 示例中明确加入 `format: "json"`，并在页面 Body 说明中逐项解释四种输出格式，仍不使用 `params` 或参数占位符。

`v1.1.12` 放宽读取型 SQL 的语法兼容性：不再使用通用解析器作为完整 SQL 语法白名单。未配置访问范围时，SELECT/只读 CTE 的具体方言由目标数据库判断，中文别名、中文对象名、聚合、CASE、窗口函数和复杂表达式不会仅因通用解析器不认识而被拒绝。使用完整 SQL 直接执行，支持 PostgreSQL 原生 `?`、`?|`、`?&` JSON 运算符，不恢复 `params` 接口。

平台仍区分真正的 SQL 与字符串、标识符和注释，并拦截多语句、写操作、DDL、SELECT INTO/文件导出和加锁查询；保留连接只读、查询超时和最大行数。MySQL 可执行版本注释也纳入检查。复杂字符串转义存在安全歧义时，使用两个单引号转义单引号。

已配置单 Schema 的 PG/Oracle 仍执行访问范围检查，中文名称会在解析时临时映射并在执行前还原；无法完整验证范围的特殊方言仍返回 400，不会绕过 Schema 限制。Oracle 未配置 Schema 时不再要求完整 AST，但仍禁止数据库链接。视图、同义词和函数内部行为取决于数据库权限，因此目标账号必须只授予所需对象的只读权限，不能使用 DBA/高权限账号。

```bash
curl -X POST "http://localhost:3001/api/v1/query" \
  -H "Authorization: Bearer dbp_xxx" \
  -H "Content-Type: application/json" \
  -d '{"sql":"SELECT 1","format":"json"}'
```

请求只需 `sql`，不使用 `params` 或 `?` 参数占位符。`format` 可选，默认 `json`，也支持 `csv`、`markdown` 和 `html`。平台只接受单条 `SELECT` 或只读 CTE，并执行代理配置的行数和超时限制。目标数据库账号必须同时授予只读权限，数据库权限才是最终安全边界。

- `json`：结构化 JSON，包含查询数据 `data`、列名 `columns` 等字段。
- `csv`：带表头的逗号分隔文本，可保存为 CSV 文件。
- `markdown`：标准 Markdown 表格，仅包含表头和数据，不包含标题或总数。
- `html`：HTML 表格页面，可在浏览器中显示。

## Oracle 11g

类型选择 Oracle，端口默认 1521，连接模式可选 Service Name 或 SID，名称填写到对应字段。
API 使用 `dbType: "oracle"`、`database: "服务名或SID"`、`oracleConnectionMode: "SERVICE_NAME"`（默认）或 `"SID"`。
Oracle 使用内置 JDBC Thin 驱动 `ojdbc8:19.32.0.0` 和同版本字符集扩展 `orai18n`，支持 `ZHS16GBK` 等字符集，不需要安装 Oracle 客户端或修改数据库字符集。

`v1.1.9` 修复连接时提示 `Non supported character set ... ZHS16GBK` 的问题，增加中文字符集往返转换回归测试及安全错误提示。升级保留原数据目录与主密钥；无需修改现有代理配置。字符集扩展依据 [Oracle JDBC Globalization Support](https://docs.oracle.com/en/database/oracle/oracle-database/19/jjdbc/globalization-support.html)。

```json
{"sql":"SELECT 1 AS RESULT FROM DUAL","format":"json"}
```

11g 分页示例：`SELECT * FROM HR.EMPLOYEES WHERE ROWNUM <= 10`。不支持 MySQL 的 `LIMIT` 或 12c 的 `FETCH FIRST`。
Oracle Schema 留空时使用登录账号的默认模式及对象权限；填写 HR 后，`FROM EMPLOYEES` 与 `FROM HR.EMPLOYEES` 均可用，其他 Schema 的直接表引用返回 403。
普通模式名转大写，双引号名称保留大小写；允许 `DUAL`/`SYS.DUAL`，禁止数据库链接。
视图、私有同义词及函数内部的间接访问仍由数据库权限决定。数据库账号也应仅有所需的只读权限。
平台每次 Oracle 查询使用只读事务，关闭连接前读取 LOB 内容，并在结束时回滚恢复连接状态。

目标 Oracle 11.2.0.1.0 尚需该实库验证；它不在当前驱动官方认证矩阵中。
不能用较新版本数据库的测试代替此版本认证。若出现 ORA-28040，会提示认证协议不兼容，不自动降低认证配置。

可选实库集成测试通过 `SQL_PROXY_ORACLE_TEST_URL`、`SQL_PROXY_ORACLE_TEST_USERNAME`、
`SQL_PROXY_ORACLE_TEST_PASSWORD` 设置连接；使用只读测试账号，无需创建、修改数据库对象。

## PostgreSQL Schema

PostgreSQL 的 Schema 填写一个实际名称（区分大小写，不需要外层双引号）。配置为 `a` 时，
`SELECT * FROM users` 和 `SELECT * FROM a.users` 均可使用，直接引用其他 Schema 的表或视图返回 403。
连接测试会验证该 Schema 存在且账号有 USAGE 权限。留空则沿用数据库默认搜索路径及账号权限。
此限制覆盖 SQL 中直接引用的对象；允许的视图和函数内部访问仍由数据库权限决定。
MySQL 和达梦的 Schema 行为保持不变。

## x86_64 离线构建与部署

```bash
bash build_x86.sh
```

脚本输出 `release/sql-proxy-platform.tar`、`release/deploy.sh` 和外部配置。把整个 `release` 目录上传到 Linux 服务器后执行：

```bash
bash deploy.sh
```

部署脚本会生成 `secrets.env`、创建 `data` 持久化目录、启动容器并检查健康状态。升级前必须一起备份 `data` 和 `secrets.env`；丢失原主密钥后，已保存的数据库密码与 Token 无法恢复。

如果通过 HTTPS 反向代理访问，请把 `config/application-prod.yml` 中的会话 Cookie `secure` 改为 `true` 后重启容器。
