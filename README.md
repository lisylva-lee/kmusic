# KMusic

Android 端音乐播放器。支持多音源聚合搜索、本地音乐、WebDAV 以及无损 / DSD 播放，界面针对手机、平板和车机横屏做了适配。

## 功能

- **多音源聚合**：搜索一次同时查酷我、网易云、咪咕，结果合并去重；播放时按优先级自动选择可用音源，无需手动切换
- **音质选择**：128k / 320k / FLAC / FLAC 24bit，可按需开启「无损降级」
- **本地音乐**：扫描系统媒体库，也可指定任意文件夹（SAF），支持增量扫描与缓存
- **WebDAV**：连接自己的 WebDAV 服务器直接播放，支持自建 NAS
- **歌单**：收藏外部歌单、创建自己的歌单、收藏单曲、「最近播放」
- **歌词**：逐行滚动、拖动定位、歌词不对时可手动换源
- **音效**：均衡器（10 段）、低音增强、环绕、混响、响度归一、响度增强、省电播放（offload）
- **DSD**：DSF / DFF，DSD64 / 128 / 256 原生解码
- **播放缓存**：默认 1GB，听过的歌二次播放不耗流量
- **其他**：睡眠定时、播放速度、后台播放与通知栏控制、媒体会话（车机 / 蓝牙 / 手表可控）
- **界面**：深色主题，手机竖屏 / 横屏 / 平板 / 车机宽屏自适应布局

## 安装

从 [Releases](https://github.com/lisylva-lee/kmusic/releases) 下载 APK 安装。

- 最低 Android 8.0（API 26）
- 支持 arm64-v8a 与 armeabi-v7a

## 构建

需要 JDK 17 与 Android SDK。

```bash
git clone https://github.com/lisylva-lee/kmusic.git
cd kmusic
cp local.properties.example local.properties   # 填入 sdk.dir
./gradlew :app:assembleDebug
```

`local.properties` 已在 `.gitignore` 中。要打正式包，再在其中补上自己的 keystore 配置（`KMUSIC_STORE_FILE` / `KMUSIC_STORE_PASSWORD` / `KMUSIC_KEY_ALIAS` / `KMUSIC_KEY_PASSWORD`）；不填则使用默认调试签名。

## 第三方音源说明

在线音源均通过公开接口获取，不包含任何私有凭证或付费账号。音源可用性取决于对应服务的公开接口状态，本项目不对其稳定性与合法性作任何担保。请自行确认所在地区的使用合规性，仅用于个人学习与测试。

## 许可证

[MIT](LICENSE)
