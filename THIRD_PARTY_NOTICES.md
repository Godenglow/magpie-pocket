# 第三方来源与授权

## Magpie

本项目的共享文件协议、统计与价格规则参考并移植自 [yetone/magpie](https://github.com/yetone/magpie)，基线提交 6acf5ee5c38865eae2fe47e879905120b2ca272c。
启动和顶部标识保留 build/icon/magpie.svg 与 magpie-small.svg 的路径。
Copyright (c) 2026 yetone。MIT 授权全文见 [magpie-icons.txt](app/src/main/assets/licenses/magpie-icons.txt)，该声明随 APK 打包；该上游授权也适用于移植/改编的相关实现。

## models.dev

内置压缩价格表与可更新的公开数据来自 [models.dev](https://models.dev) / [anomalyco/models.dev](https://github.com/anomalyco/models.dev)。
Copyright (c) 2025 models.dev。MIT 授权全文见 [models-dev.txt](app/src/main/assets/licenses/models-dev.txt)，随 APK 打包。
公开数据可能滞后或不完整，详见计算口径文档。

## 构建与依赖

Gradle Wrapper 用于构建，Gradle 项目采用 Apache License 2.0。AndroidX、Kotlin、Compose、OkHttp、序列化等依赖维持各自原始许可，不因本项目采用 MIT 而改变。具体依赖版本见 gradle/libs.versions.toml，第三方包内的许可声明应予保留。

## 演示与测试

docs/images 下的截图来自本项目隔离模拟器中的虚构账号与虚构请求；测试 fixture 口令仅用于合成样本，不属于真实 WebDAV 账号。
