# 🤖 MCBot - Minecraft 机器人

基于 [MCPL (MCProtocolLib)](https://github.com/GeyserMC/MCProtocolLib) 的 Minecraft 机器人，支持在线/离线模式，具备完整的游戏协议处理能力。

## ✨ 功能特性

| 功能 | 状态 | 说明 |
|------|------|------|
| 🔐 Yggdrasil 在线认证 | ✅ | 支持 LittleSkin 等外置登录 |
| 📡 离线模式 | ✅ | 适用于 `online-mode=false` 的服务器 |
| ⚙️ 自动配置阶段 | ✅ | 品牌数据、已知包、注册表全自动 |
| 💓 心跳自动响应 | ✅ | 不重复响应，防止超时断开 |
| 💚 保活线程 | ✅ | 每秒发送位置包保持在线 |
| 💀 死亡自动重生 | ✅ | 检测死亡并自动点击重生 |
| 💬 聊天消息显示 | ✅ | 显示玩家聊天和系统消息，真实玩家名 |
| 🎛️ 控制台命令 | ✅ | 发送聊天、执行游戏命令 |
| 🔄 自动重连 | ✅ | 断开后自动重连（最多10次） |
| 👥 多角色选择 | ✅ | 同一账号多角色可选择 |
| 📋 玩家加入/离开提示 | ✅ | 自动检测并显示 |

## 🚀 快速开始

### 1. 下载文件

- **[MCBot](https://github.com/fingtest6/mcbot/actions)** - 从 GitHub Actions 下载最新构建
  - 点击最新的 workflow run
  - 在 Artifacts 中下载 `mcbot-1`
- **[authlib-injector](https://authlib-injector.yushi.moe/)** - 外置登录框架（在线模式需要）

### 2. 放置文件

将下载的文件放在同一文件夹中：

```
mcbot/
├── mcbot-1.0-SNAPSHOT-all.jar    # MCBot 主程序
└── authlib-injector-1.2.7.jar    # 外置登录（在线模式需要）
```

### 3. 启动

**在线模式（Yggdrasil / LittleSkin）：**
```bash
java -javaagent:authlib-injector-1.2.7.jar=https://littleskin.cn/api/yggdrasil -jar mcbot-1.0-SNAPSHOT-all.jar
```

**离线模式：**
```bash
# 在 config.properties 中设置 mc.auth-mode=offline
java -jar mcbot-1.0-SNAPSHOT-all.jar
```

### 4. 首次配置

首次运行会进入交互式配置向导：

```
╔══════════════════════════════════════╗
║     首次运行 - 配置 MCBot           ║
╚══════════════════════════════════════╝

── 服务器配置 ──
服务器地址 (默认 localhost): xxxxx.xxx
服务器端口 (默认 25565): xxxxx

── 认证配置 ──
认证模式 (1=在线, 2=离线, 默认 1): 1
Yggdrasil API (默认 https://littleskin.cn/api/yggdrasil): 
邮箱: example@mail.com
密码: ********

── 角色选择设置 ──
  true  = 记住角色，每次启动自动使用 (适合单开)
  false = 每次启动都询问选择角色 (适合多开)
记住角色？(默认 true): 

发现 3 个角色:
  1. User1 (UUID: 00000005...)
  2. User2 (UUID: 00000004...)
  3. User3 (UUID: 00000008...)
请选择角色 (1-3): 1
✅ 已选择角色: User1
```

## ⚙️ 配置文件

自动生成 `config.properties`：

```properties
# MCBot 配置文件
# 生成时间: xxxxxxx

# 服务器设置
mc.host=xxxx.xxx
mc.port=xxxxx

# 认证设置
mc.auth-mode=online
mc.yggdrasil-api=https://littleskin.cn/api/yggdrasil
mc.email=example@mail.com
mc.password=your_password

# 角色选择
# mc.remember-profile=true  记住角色，启动时不询问
# mc.remember-profile=false 每次启动都询问选择角色
mc.remember-profile=true
mc.username=Name

# 其他设置
# mc.language=zh_CN
```

## 📦 本地构建

### 环境要求
- **Java 21+** - [下载](https://adoptium.net/)
- **Gradle 9.x** - 项目自带 wrapper

### 构建步骤

```bash
# 1. 克隆项目
git clone https://github.com/fingtest6/mcbot.git
cd mcbot

# 2. 构建 Fat Jar
./gradlew clean fatJar

# 3. 运行
java -jar build/libs/mcbot-1.0-SNAPSHOT-all.jar
```

### GitHub Actions

推送代码自动构建，创建 `v*` 标签自动发布 Release。

```bash
# 创建 Release
git tag v1.0.0
git push origin v1.0.0
```

## 🔧 技术栈

| 技术 | 说明 |
|------|------|
| [MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib) | Minecraft 协议库 |
| [authlib-injector](https://github.com/yushijinhun/authlib-injector) | 外置登录框架 |
| [Gson](https://github.com/google/gson) | JSON 解析 |
| [Netty](https://netty.io/) | 网络框架 |
| Java 21 | 运行环境 |

## ❓ 常见问题

### Q: 启动后立即断开？
确保 `authlib-injector` 参数正确，API 地址可访问。

### Q: 15秒超时断开？
这是服务器心跳配置问题。MCPL 默认监听器会自动处理心跳，无需手动干预。

### Q: 如何多开？
在 `config.properties` 中设置 `mc.remember-profile=false`，每次启动选择不同角色。

### Q: 支持哪些 Yggdrasil API？
支持所有标准 Yggdrasil API，如 [LittleSkin](https://littleskin.cn/)、自定义皮肤站等。

### Q: 如何获取 authlib-injector？
从 [authlib-injector 官网](https://authlib-injector.yushi.moe/) 下载最新版本。

## 📝 许可证

MIT License

## ⚠️ 免责声明

本项目仅供学习和研究使用。使用者应遵守 Minecraft EULA 和相关服务器规则。请勿用于非法用途。