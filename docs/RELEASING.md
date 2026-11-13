# 发布流程

## 版本号

`app/build.gradle.kts` 中的 `versionName` / `versionCode` 是唯一版本来源；标签名是 `v` + `versionName`。

发布新版本：

1. `versionName` 改成 `x.y.z`，`versionCode` 加一（不能回退，否则无法覆盖安装）。
2. 在 [CHANGELOG.md](../CHANGELOG.md) 顶部加 `## x.y.z - YYYY-MM-DD` 小节；Release 说明会从这个小节自动提取。
3. 提交并推送 `main`，等 `Android` 工作流通过。
4. 打标签并推送：

   ```bash
   git tag -a v0.10.0 -m "Magpie Pocket 0.10.0"
   git push origin v0.10.0
   ```

推送标签会触发 `.github/workflows/release.yml`，它会：

- 校验标签与 `versionName` 完全一致，不一致直接失败；
- 安装 SDK，用仓库 Secrets 里的 keystore 构建；
- 跑 `testDebugUnitTest`，再 `assembleRelease`；
- 用 `apksigner` 核对签名证书指纹，不是发布证书就失败（防止误用 debug key 发布）；
- 产出 `magpie-pocket-<版本>.apk` 与同名 `.sha256`；
- 创建 GitHub Release，标题为 `Magpie Pocket <版本>`，附上两个文件和从 CHANGELOG 提取的说明。

工作流可重复执行：Release 已存在时会更新说明并覆盖上传资产，所以修好问题后重跑任务即可。

## 签名密钥

正式包使用固定密钥签名，密钥不在仓库里。CI 从仓库 Secrets 读取：

| Secret | 内容 |
| --- | --- |
| `MAGPIE_KEYSTORE_BASE64` | keystore 文件的 base64（`base64 -w0 release.jks`） |
| `MAGPIE_KEYSTORE_PASSWORD` | keystore 口令 |
| `MAGPIE_KEY_ALIAS` | 密钥别名 |
| `MAGPIE_KEY_PASSWORD` | 密钥口令 |

当前证书的 SHA-256 指纹（同时写在 `release.yml` 的 `EXPECTED_CERT_SHA256` 里）：

```
C0:28:2D:BA:02:3C:7F:62:B1:EC:9B:6E:87:DC:79:71:B1:33:C9:9F:40:B6:4D:4E:A4:0E:2D:92:98:6F:7B:EC
```

**keystore 和口令必须离线备份。** 丢失后无法再发布可覆盖升级的版本，用户只能卸载重装；泄露则任何人都能发布签名相同的包。更换密钥时，要同步更新 `release.yml` 中的指纹、本文档和 Release 说明。

## 本机构建正式签名包

用环境变量（与 CI 相同）：

```powershell
$env:MAGPIE_KEYSTORE_PATH = "C:\path\to\release.jks"
$env:MAGPIE_KEYSTORE_PASSWORD = "..."
$env:MAGPIE_KEY_ALIAS = "magpie-pocket"
$env:MAGPIE_KEY_PASSWORD = "..."
.\gradlew.bat assembleRelease
```

或者在仓库根目录放一个不会被提交的 `keystore.properties`：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=magpie-pocket
keyPassword=...
```

两者都没有时，`assembleRelease` 会退回 debug key 并在配置阶段打印提示；这种包不能用于发布，工作流的指纹校验也会拦住它。

校验签名：

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## 发布前检查

- 单元测试全绿（`.\gradlew.bat testDebugUnitTest`）。
- 在真机或模拟器上装一次本版本，确认可以覆盖安装旧版本并读取已有归档。
- CHANGELOG 小节里的内容对用户可读，不包含内部代号或未发布的功能。
- 没有把 keystore、`keystore.properties`、口令或真实 WebDAV 凭据写进仓库。
