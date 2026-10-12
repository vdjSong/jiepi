# 洁癖播放器（jiepi）

一款纯本地、无广告、轻量干净的 Android 音乐播放器。

## 特性

- **纯本地播放**：只扫描设备本地音乐文件，不联网、无广告、不收集任何数据
- **少权限**：仅申请播放必需的权限，不碰通讯录、不碰定位
- **轻量体积**：安装包 < 100MB，不臃肿
- **丰富音效**：内置均衡器、混响、变速变调等音效
- **沉浸体验**：霓虹风格 UI、摇一摇切歌、播放记忆、启动自动播放
- **回收站保护**：删除歌曲先进回收站，可一键恢复，绝不直接删用户数据
- **开源声明**：应用内可查看全部第三方组件许可（英文原文 + 中文参考翻译）

## 技术栈

- 语言：Kotlin
- 播放内核：Media3 ExoPlayer（1.5.1）
- 变速变调：Rubber Band（GPL-2.0-or-later）
- 最低系统：Android 15（minSdk 34）

## 构建

使用 Android Studio 打开本工程，等待 Gradle 同步完成后直接 `Build > Build APK(s)` 即可。

```
./gradlew assembleDebug
```

## 许可

本项目以 [GPL-3.0](LICENSE) 许可证开源。

### 第三方开源组件

| 组件 | 作者 | 许可证 |
| --- | --- | --- |
| AndroidX（10 个组件） | Google（Android Open Source Project） | Apache-2.0 |
| Rubber Band 4.0.0 | Chris Cannam / Particular Programs Ltd | GPL-2.0-or-later |
| pinyin4j 2.5.1 | Li Min | GPL-2.0-or-later |

各组件完整声明（名称 / 版本 / 作者 / 来源地址 / 许可证原文链接）见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)；许可证原文（英文）与中文参考翻译也可在应用内「更多 → 开源声明」页查看。
