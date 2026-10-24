# Payload Extract GUI

[English](#english) | [中文](#中文)

---

## English

A cross-platform GUI application for extracting Android OTA `payload.bin` files, built with [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) and [Miuix](https://github.com/YuKongA/miuix).

### Features

- **Local & remote extraction** — supports `payload.bin`, OTA ZIP, and HTTP/HTTPS URLs
- **Android file access** — import files and choose output folders with the system picker, without All files access
- **OTA metadata display** — version, partitions, block size, security patch
- **Per-partition extraction** — progress display and optional SHA256 verification
- **Multi-threaded** — parallel decompression and writing
- **Cross-platform UI** — consistent look and feel across Desktop and Android

On Android, select a local output folder before extracting. The app remembers the authorized location.

### Supported Platforms

| Platform | Architecture      |
| -------- | ----------------- |
| Windows  | x86_64            |
| macOS    | aarch64           |
| Linux    | x86_64, aarch64   |
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

- **本地与远程提取** — 支持 `payload.bin`、OTA ZIP 和 HTTP/HTTPS 链接
- **Android 文件访问** — 通过系统文件选择器导入文件、选择输出目录，无需“所有文件访问权限”
- **OTA 元数据展示** — 版本、分区、块大小、安全补丁
- **逐分区提取** — 显示提取进度，支持可选 SHA256 校验
- **多线程** — 并行解压和写入
- **跨平台 UI** — 桌面端和 Android 端统一的界面风格

Android 提取前请选择本地输出目录，应用会记住已授权的位置。

### 支持平台

| 平台    | 架构              |
| ------- | ----------------- |
| Windows | x86_64            |
| macOS   | aarch64           |
| Linux    | x86_64, aarch64   |
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
