# ComfyChair Media 删除扩展 — 安装指南

> 本插件让 ComfyChair 安卓端图库的「永久删除」真正从服务器磁盘删除 Input/Output 图片文件。
> 它是一个 **ComfyUI 后端路由插件**（`custom_nodes` 扩展），不是工作流节点——安装后不会在工作流列表出现任何新节点。
>
> ⚠️ 没有安装本插件时，App 内的服务器图库浏览、多选等一切功能照常可用，只是「永久删除」显示为不可用，
> 且原图库的删除只清理 ComfyUI 执行历史（磁盘文件保留）。

---

## 一、你需要的准备

| 项 | 要求 |
|---|---|
| 操作系统 | Linux（ComfyUI 所在主机） |
| ComfyUI | 标准部署，含 `custom_nodes/` 目录 |
| Python 依赖 | 无新增——只用 ComfyUI 自带的 `aiohttp` 和标准库 |
| 权限 | 能读写 ComfyUI 目录、能修改服务启动环境、能重启 ComfyUI 服务 |
| 密钥 | 一个独立随机密钥，**至少 32 字符**（见下文生成方法） |

安装前请**备份** `custom_nodes/` 目录与 Input/Output 目录清单。

### 生成密钥

在服务器上执行（任选其一）：

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
# 或
openssl rand -base64 32
```

记下输出，例如 `Kx9mQ2...长随机串...==`。
**这不是 ComfyUI 的登录密码**，是本插件专用的独立密钥。不要复用任何已有密码。

---

## 二、安装步骤

### 1. 复制插件目录

把本仓库的 `server/comfychair_media/` 整个目录复制到 ComfyUI 的 `custom_nodes/` 下：

```bash
cp -r /path/to/comfychair/server/comfychair_media \
      /path/to/ComfyUI/custom_nodes/
```

安装后的结构：

```
ComfyUI/custom_nodes/comfychair_media/
├── __init__.py     # 入口：注册路由，导出零工作流节点
├── service.py      # 删除服务实现
└── README.md       # 协议与安全设计说明（英文）
```

> ⚠️ **升级时**：不要覆盖/删除插件目录下运行期生成的 `missing.json`（删除墓碑日志），
> 它是数据一致性记录，丢失或损坏会使删除功能**安全失效**（fail closed）。

### 2. 配置环境变量（推荐 LAN 模式）

家庭/可信局域网场景使用最简单的 **LAN 模式**，只需要一个密钥。
把下面的变量加进 ComfyUI 服务的启动环境（systemd unit、docker compose、启动脚本均可）：

```bash
# 必填：独立的删除密钥（就是上面生成的那个，≥32 字符）
COMFYCHAIR_DELETE_SECRET=<你的密钥>
# 必填：显式声明为 lan 模式（默认是 proxy，不设此项插件不启用删除）
COMFYCHAIR_AUTH_MODE=lan
```

systemd 示例（在 unit 的 `[Service]` 段加两行）：

```ini
Environment=COMFYCHAIR_DELETE_SECRET=Kx9m...你的密钥...
Environment=COMFYCHAIR_AUTH_MODE=lan
```

docker compose 示例：

```yaml
environment:
  - COMFYCHAIR_DELETE_SECRET=Kx9m...你的密钥...
  - COMFYCHAIR_AUTH_MODE=lan
```

**可选变量**（一般不用设）：

| 变量 | 说明 |
|---|---|
| `COMFYCHAIR_TOMBSTONE_FILE` | 墓碑日志绝对路径。默认 `custom_nodes/comfychair_media/missing.json`，插件目录可持久写时无需设置。如自定义，须指向**私有、受信、服务可写**且不在 input/output 媒体目录内的位置 |

未配置或配置不完整（密钥缺失/过短、模式非法、日志损坏）时，插件**不会启用删除**，浏览器访问
`/comfychair/v1/media/capabilities` 会看到 `"delete": false`。

> 🔒 **反向代理模式**（默认 `proxy`）：适用于 ComfyUI 暴露在公网/由带认证的反代保护的部署，
> 需要额外配置 `COMFYCHAIR_PROXY_SECRET`、`COMFYCHAIR_TRUSTED_PROXY_IP` 并由反代注入管理员身份头。
> 详细要求见 `README.md` 的 "Optional proxy boundary" 一节。家庭内网直连场景请用 LAN 模式。

### 3. 重启 ComfyUI

```bash
# systemd
sudo systemctl restart comfyui
# docker
docker compose restart comfyui
```

重启时机请选择维护窗口。插件加载失败不会影响 ComfyUI 本身（只是没有删除路由）。

### 4. 验证插件已生效

在服务器本机：

```bash
curl -s http://127.0.0.1:8188/comfychair/v1/media/capabilities
```

期望输出（LAN 模式、本机访问时 `delete` 为 true）：

```json
{"version": 1, "delete": true, "max_batch": 50, "permanent": true,
 "consistency": "persistent_tombstones", "auth_modes": ["lan", "proxy"], "auth_mode": "lan"}
```

如果 `"delete": false`，检查环境变量是否进了服务进程：

```bash
sudo systemctl show comfyui | grep Environment
# 或 docker exec <容器> env | grep COMFYCHAIR
```

---

## 三、手机端配置

1. 打开 ComfyChair → 底栏 **Gallery** → 顶栏 **Cloud 图标** 进入服务器图库
2. 点右上角 **ⓘ（信息）** 展开 → **Deletion key（删除密钥）**
3. 粘贴**同一个** `COMFYCHAIR_DELETE_SECRET` 密钥
4. 打开 **Trusted LAN deletion (allow HTTP)** 开关（局域网 HTTP 必须开，阅读警告）
5. 点 **Store securely** —— 密钥加密存进手机 Keystore，不进系统备份

完成后回到图库，**长按任意图片**即可进入多选模式；选中后点 **🗑 永久删除**。

> 换服务器地址/端口/协议、或切换到另一台服务器时，密钥与授权**不会**跟随复用，需要重新输入一次。

---

## 四、工作方式与安全边界（重要，请阅读）

### 删除流程（两步确认，防误删）

1. **prepare**：App 上传要删的文件清单（type/subfolder/filename），插件对每个文件做
   **只读快照**（设备号/inode/时间戳/大小），返回一次性操作令牌，120 秒有效
2. **delete**：App 凭令牌执行。插件再次校验文件身份未变后才 unlink，
   逐文件返回 `success / not_found / conflict / busy / invalid / failed`
   ——**部分成功是显式的**，App 会逐文件显示结果
3. 删除前先把删除意图写入 `missing.json` 墓碑日志（fsync），崩溃后不会出现"删了但不知道删了什么"

### 安全守卫

- **队列互斥**：ComfyUI 有任何排队/运行中的任务时删除直接拒绝（`busy`），避免删掉正在被引用的文件
- **路径守卫**：拒绝绝对路径、`..` 穿越、符号链接（每级目录 `O_NOFOLLOW`）、硬链接、非普通文件、非图片扩展名
- **不碰这些**：执行历史（history）、共享预览、assets 数据库、目录递归——只删你明确选中的那几张普通图片文件
- **明文 HTTP 风险**：LAN 模式下密钥和操作令牌走 HTTP 明文。仅限可信内网，**绝不要把端口暴露到公网**。
  被抓包者可重放密钥删除文件。需要更严格的边界请用 proxy 模式 + HTTPS

### 已知限制（如实告知）

- **墓碑只对本 App 生效**：物理删除后，其他 ComfyUI 前端/历史页面仍可能显示这些图片的记录（文件已不在）
- **同名重建会被隐藏**：删除后又生成同名文件，App 会按墓碑继续隐藏；需管理员按 README 的指引维护日志
- **不防外部写入竞态**：删除期间请勿让其他程序/插件写同一媒体目录（维护窗口内操作）
- 墓碑上限 5000 条，不自动清理；接近上限前请维护

---

## 五、卸载 / 禁用

- **临时禁用删除**：去掉 `COMFYCHAIR_DELETE_SECRET` 环境变量并重启——浏览不受影响
- **彻底卸载**：从 `custom_nodes/` 删除 `comfychair_media` 目录并重启。
  如需保留删除历史记录，先备份 `missing.json`
- 手机端清空 App 里的删除密钥（密钥输入框留空保存）即可撤销授权

## 六、运行测试（可选）

```bash
pip install pytest pytest-asyncio aiohttp
python -m pytest server/tests -q   # 84 项，全部使用临时目录，不接触真实媒体
```
