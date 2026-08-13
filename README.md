

# Payload Extract GUI

[English](#english) | [中文](#中文)

---

## English

A cross-platform GUI application for extracting Android OTA `payload.bin` files, built with [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) and [Miuix](https://github.com/YuKongA/miuix).

### Features

- **Local & remote extraction** — open local `payload.bin` / OTA ZIP files, or parse remote OTA ZIP via HTTP/HTTPS URL (streaming, no full download required)
- **OTA metadata display** — version, partitions, block size, security patch
- **Per-partition extraction** — extract individual or all partitions with SHA256 verification
- **Multi-threaded** — configurable thread count for concurrent extraction
- **Cross-platform UI** — consistent look and feel across Desktop and Android

### Supported Platforms

| Platform | Architecture      |
| -------- | ----------------- |
| Windows  | x86_64            |
| macOS    | aarch64           |
| Linux    | x86_64            |
| Android  | arm64-v8a, x86_64 |

### Build

#### Prerequisites

- JDK 25
- [Rust toolchain](https://rustup.rs/)

#### Desktop

```bash
./gradlew createReleaseDistributable
```

#### Android

```bash
./gradlew :android:assembleRelease
```

### Credits

- [payload_extract_rs](https://github.com/YuKongA/payload_extract_rs) — payload extractor library
- [Miuix](https://github.com/YuKongA/miuix) — UI component library

### License

[GPL-2.0](LICENSE)

---

## 中文

跨平台的 Android OTA `payload.bin` 提取工具，基于 [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) 和 [Miuix](https://github.com/YuKongA/miuix) 构建。

### 功能特性

- **本地与远程提取** — 打开本地 `payload.bin` / OTA ZIP 文件，或通过 HTTP/HTTPS URL 解析远程 OTA ZIP（流式传输，无需完整下载）
- **OTA 元数据展示** — 版本、分区、块大小、安全补丁
- **逐分区提取** — 支持单独或全部提取，可选 SHA256 校验
- **多线程** — 可配置线程数，并发提取
- **跨平台 UI** — 桌面端和 Android 端统一的界面风格

### 支持平台

| 平台    | 架构              |
| ------- | ----------------- |
| Windows | x86_64            |
| macOS   | aarch64           |
| Linux   | x86_64            |
| Android | arm64-v8a, x86_64 |

### 构建

#### 环境要求

- JDK 25
- [Rust 工具链](https://rustup.rs/)

#### 桌面端

```bash
./gradlew createReleaseDistributable
```

#### Android

```bash
./gradlew :android:assembleRelease
```

### 致谢

- [payload_extract_rs](https://github.com/YuKongA/payload_extract_rs) — payload 提取库
- [Miuix](https://github.com/YuKongA/miuix) — UI 组件库

### 许可证

[GPL-2.0](LICENSE)
