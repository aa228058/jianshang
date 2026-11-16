# GitHub Actions 构建说明

## 需要配置的 Secrets

打开仓库 **Settings → Secrets and variables → Actions → New repository secret**，逐个添加：

| Secret 名 | 值 | 必需 |
|---|---|---|
| `APP_API_ID` | 你的 api_id（如 `38399143`） | **必需** |
| `APP_API_HASH` | 你的 api_hash | **必需** |
| `RELEASE_STORE_BASE64` | 签名文件的 base64（见下） | 可选 |
| `RELEASE_STORE_PASSWORD` | keystore 密码 | 可选 |
| `RELEASE_KEY_ALIAS` | key 别名 | 可选 |
| `RELEASE_KEY_PASSWORD` | key 密码 | 可选 |

前两个不填 → 构建会退回仓库自带的公开 api_id（多人共用，容易限流，但能编能跑）。
后四个不填 → 用仓库里的 debug 签名，能装但不适合发布。

## 生成 RELEASE_STORE_BASE64

```bash
keytool -genkeypair -keystore jianshang.jks -alias jianshang \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass 你的密码 -keypass 你的密码 \
        -dname "CN=Jianshang, O=Jianshang, C=CN"

base64 -w 0 jianshang.jks        # macOS: base64 -i jianshang.jks | tr -d '\n'
```

把输出的那一长串粘进 `RELEASE_STORE_BASE64`。

## 怎么触发

- 仓库 **Actions** 页 → `Build Release APK` → **Run workflow**
- 或者打标签：`git tag v1.0.0 && git push --tags`

构建完在 **Artifacts** 里下载 `jianshang-release.zip`。

## 耗时和资源

- 首次构建 **40~70 分钟**（JNI 全量编译），之后有缓存会快一些
- 免费 runner：4 核 / 16GB 内存 / 14GB 磁盘 —— 所以 workflow 里先跑了 `free-disk-space` 腾出约 30GB
- 超过 150 分钟会超时（workflow 里设了 `timeout-minutes: 150`）

## 注意

- Pull Request 来自 fork 时拿不到 Secrets，构建会走公开 api_id
- 构建产物保留 30 天
