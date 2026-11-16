<div align="center">

# 🏔 见山客户端 · Jianshan Client

**面向 Android 的 Telegram 第三方客户端**
**An independent Telegram client for Android**

[![License: GPL v2](https://img.shields.io/badge/License-GPL--2.0-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android-brightgreen)

[简体中文](#-简体中文) · [English](#-english) · [问题反馈](https://t.me/jianshang_888)

</div>

---

## 🇨🇳 简体中文

### 简介

**见山客户端**是基于 [Telegram Android](https://github.com/DrKLO/Telegram) 源码（GPL-2.0）构建的独立客户端，
在 [Mango 客户端](https://github.com/2492866096/Mango) 的基础上继续开发。

- **包名**：`app.jianshang.im`
- **不是 Telegram 官方应用**，也不隶属于 Telegram。Telegram 名称及相关商标归其权利人所有。

### 相对上游的主要改动

| 模块 | 内容 |
|---|---|
| **资料卡「活动」tab** | 把「你和 TA 在共同群组里 TA 发过的消息」按 日期 → 群 → 消息 三级展示；3 并发 + 限流保护，撞 `FLOOD_WAIT` 自动冷却 |
| **min 用户资料修复** | 修复部分成员资料页空白的问题（`access_hash` 上下文绑定导致 `USER_ID_INVALID`），用 `inputUserFromMessage` 取回完整资料并持久化 |
| **防撤回样式** | 撤回的消息不再整体半透明；标记改为浅灰的「已撤回」，内容完整保留 |
| **后台保活** | `specialUse` 前台服务 + 周期性 `resumeNetworkMaybe()`，配合电池优化白名单引导 |

### 源码构建

```bash
# 1) 配置 API 凭据（https://my.telegram.org → API development tools）
cp local.properties.example local.properties
# 编辑 local.properties：
#   APP_API_ID=你的 api_id
#   APP_API_HASH=你的 api_hash

# 2) 配置签名（gradle.properties 里的 RELEASE_KEY_* 是示例值，正式发版必须换）

# 3) 构建
./gradlew :TMessagesProj_App:assembleRelease
```

不填 `APP_API_ID` / `APP_API_HASH` 也能编译，会退回仓库自带的公开值——**那是多人共用的，正式发版请务必换成自己的**。

### 品牌替换

仓库自带 `rebrand.sh`，改名/换联系方式一条命令：

```bash
./rebrand.sh "见山客户端" "客服用户名" "开发者用户名"          # 真改
./rebrand.sh "见山客户端" "客服用户名" "开发者用户名" --dry    # 只看会改哪些
```

### 反馈

- 客服 / 开发者：[@jianshang_888](https://t.me/jianshang_888)

### 许可证

GPL-2.0。源码基于 Telegram for Android（Copyright Nikolai Kudashov）与 Mango 客户端，
按 GPL-2.0 要求保留原作者版权与许可声明，详见 [LICENSE](LICENSE)。

---

## 🇬🇧 English

**Jianshan Client** is an independent Telegram client for Android, built on the
[Telegram Android](https://github.com/DrKLO/Telegram) source (GPL-2.0) and continuing from
the [Mango client](https://github.com/2492866096/Mango).

- Package: `app.jianshang.im`
- Not affiliated with Telegram.

### Highlights

- **"Activities" tab** on user profiles: messages a contact posted across your common groups,
  grouped by date → group → message, with flood-wait protection.
- **Min-user profile fix**: repairs blank profiles caused by context-bound `access_hash`
  by re-fetching via `inputUserFromMessage` and persisting the full user.
- **Anti-revoke styling**: revoked messages keep their content, marked with a subtle grey "revoked" label.
- **Keep-alive**: `specialUse` foreground service with periodic `resumeNetworkMaybe()`.

### Build

```bash
cp local.properties.example local.properties   # set APP_API_ID / APP_API_HASH
./gradlew :TMessagesProj_App:assembleRelease
```

### License

GPL-2.0. Based on Telegram for Android (Copyright Nikolai Kudashov) and the Mango client;
original copyright and license notices are retained as required.
