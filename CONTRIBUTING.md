# 参与贡献

欢迎提 Issue 和 PR。开始之前请先读完这一页，能省掉大部分来回。

## 环境要求

- JDK 17
- Android SDK（platform 35 / build-tools 35）
- Android 11+ 真机或模拟器（功能验证用；纯编译不需要）

## 本地构建

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # 未配置签名时输出未签名包
```

Release 签名文件永远不进仓库：把签名 properties 放在仓库外，通过
`JEV_KEYSTORE_PROPS` 环境变量指定路径，或放到 `app/keystore.properties`
（已被 .gitignore 排除）。格式：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`。

## 提交前检查

- [ ] `./gradlew assembleDebug` 通过
- [ ] 不包含任何密钥、签名文件、`pipeline-test.properties`
- [ ] 不包含真实聊天截图、真实对话内容、真实联系人信息——测试与文档一律用虚构示例数据
- [ ] 改了采集相关逻辑时，说明在哪个 App 的哪个版本上验证过

## 提交方式

- 一个 PR 只做一件事，改动范围尽量小。
- commit message 一行说清意图（中文或英文均可）。
- 行为有取舍的大改动（采集方式、权限、隐私相关）请先开 Issue 讨论再动手。
