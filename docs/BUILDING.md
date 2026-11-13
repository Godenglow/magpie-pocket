# 构建与测试

## 环境

- Android Studio 或 Android SDK 命令行环境。
- JDK 17；Gradle Wrapper 8.11.1。
- Android SDK Platform 36，最低设备版本 API 26。
- 首次构建需要下载 Gradle 与 Maven 依赖。

不要修改包名来实现普通升级；当前 applicationId 是 dev.magpie.usage。源码目录名和仓库名可以不同。

Android Studio 打开仓库根目录后设置本机 SDK 路径。命令行也可在未提交的 local.properties 中配置 sdk.dir；请填写自己的路径。

## 常用命令

Windows：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

macOS / Linux：

```bash
./gradlew testDebugUnitTest assembleDebug
```

依赖已缓存时可加 --offline。debug APK 输出到 app/build/outputs/apk/debug/app-debug.apk，测试报告在 app/build/reports/tests/testDebugUnitTest/index.html。

仓库 CI 运行同一组任务并上传 debug APK 与测试报告。它不使用真实 WebDAV 账号、口令或用户用量，也不执行真实账号的云端同步。

## 合成测试向量

app/src/test/resources/fixtures/fixtures.json 由 tools/fixtures/generate_fixtures.py 生成，使用专门的虚构口令和请求。可用于验证 Python 封装与 Kotlin 解密、解析及独立计算的期望值。

重新生成需要 Python 与 cryptography：

```bash
python -m pip install cryptography
python tools/fixtures/generate_fixtures.py
```

测试中明文存在的 fixture 口令只用于这些合成向量，不是任何真实账号凭据。

## 价格快照

先下载公开的 models.dev/api.json 到自己的临时位置，再运行：

```bash
python tools/fixtures/make_price_snapshot.py /path/to/api.json
```

输出更新 app/src/main/assets/prices/models-dev.json.gz。检查 fetchedAt、价格与 canonical 字段，并保留 models.dev 授权。Python 脚本与 Android 在线更新逻辑都需要测试，不应假设公开数据结构永不改变。

## 签名与安装

仓库不包含任何私钥或签名口令。release 签名来自环境变量（CI 用仓库 Secrets）或未提交的 `keystore.properties`：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

对应环境变量是 `MAGPIE_KEYSTORE_PATH`、`MAGPIE_KEYSTORE_PASSWORD`、`MAGPIE_KEY_ALIAS`、`MAGPIE_KEY_PASSWORD`。两者都没有时，`assembleRelease` 会用构建机的 debug key 兜底，并在配置阶段打印提示；这种包只能自测，不能作为发布包。

`assembleDebug` 使用构建环境自己的 debug key，因此不同开发者/CI 的包可能无法互相覆盖安装。已有应用采用另一把签名密钥时，需要匹配签名或卸载重装；卸载可能清除本机历史和凭据。

正式包的构建与发布见[发布流程](RELEASING.md)。

## 验证范围

初始公开版本本地单元测试 105 项通过，并对 Android 16 隔离模拟器的浅深色页面、主题保存与跟随系统做了显示检查。这不等价于全部品牌真机验证。曾观察到该模拟器 ART 原生 SIGSEGV；详见[限制](LIMITATIONS.md)。
