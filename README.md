# 译读 · 双语电子书阅读器（Android）

一个面向外语阅读的安卓电子书 App：导入英文、日文等外文书，在同一页里逐段对照显示中文翻译；竖排（从右往左翻页）的日文书可以一键转成横排、从左往右阅读。

## 功能

**书籍导入**
- 格式：EPUB（EPUB 2 / 3）、TXT（自动识别 UTF-8 / GBK / GB18030 / Big5 / Shift_JIS / EUC-JP / UTF-16 编码并自动分章）、HTML
- 本地导入：选择文件（可多选）、导入整个文件夹、从其他 App“打开方式 / 分享”到本应用
- Google Drive：应用内浏览“我的云端硬盘 / 与我共享”，支持全盘搜索，Google 文档可导出为纯文本导入
- Dropbox：应用内浏览文件夹并下载导入
- 重复导入自动识别（按文件内容哈希）

**实时翻译 / 双语对照**
- 三种显示方式：关闭 / 双语对照（每段原文下方显示译文）/ 仅译文
- 先翻译当前页附近的段落，再向后翻译，翻页时自动调整优先顺序；插入译文后保持你的阅读位置不跳动
- 译文本地缓存，重读不重复请求、不重复计费
- 长按选中文字 → 菜单里的“翻译”可单独翻译词句
- 翻译引擎：
  | 引擎 | 说明 |
  | --- | --- |
  | Google ML Kit | 手机本地离线翻译，免费；质量一般，中文仅简体；首次需下载约 30MB 模型 |
  | Google Cloud Translation | 需要 API Key |
  | DeepL | 需要 API Key（免费版 Key 也可） |
  | Microsoft Azure 翻译 | 需要密钥和区域 |
  | OpenAI 兼容接口 | DeepSeek、通义千问、Kimi、OpenAI、本地 Ollama 等，填 Base URL + Key + 模型名 |
  | Anthropic Claude | 通过官方 Java SDK 调用，可选 Opus 5.5 / Sonnet 5.5 / Haiku 5.5 |
- 大模型翻译支持自定义要求（例如“人名保留原文”“语气口语化”）
- 译文语言：简体中文、繁体中文、英语、日语、韩语

**竖排书转横排**
- 自动识别竖排书（`writing-mode: vertical-rl`、`page-progression-direction="rtl"` 等）
- 默认转成横排、从左到右翻页；竖排专用的标点（︒︑﹁﹂︵︶…）自动换成横排标点，纵中横（tcy）恢复成普通文字，注音（ruby / 振假名）保留
- 也可以在“外观”里切回原书竖排、从右往左翻页

**阅读功能**
- 左右翻页（手指拖动跟随、点击左右区域翻页）或上下滚动
- 字号、字体（衬线 / 无衬线 / 等宽 / 原书字体 / 导入 TTF、OTF 字体）、行距、段距、左右和上下边距、两端对齐、首行缩进
- 5 种背景：白色、纸张、护眼绿、夜间、纯黑
- 目录（多级）、书签（带摘录，可跳转、删除）、全书搜索（结果高亮）、脚注弹窗
- 进度条拖动跳转、上一章 / 下一章、阅读进度自动保存（精确到段落中的字，换字号也不会丢位置）
- 音量键翻页、屏幕常亮、沉浸式全屏

## 安装

- 直接安装：到本仓库的 **Actions → Android build** 最新一次运行里下载 `ereader-apks`，手机一般装 `app-arm64-v8a-release.apk`（`universal` 版本适用所有设备，但体积更大）。
- 自己编译：见下方“编译”。

## 翻译引擎配置

书架右上角 ⚙ → 设置 → 翻译引擎：

- **ML Kit**：无需配置。首次翻译会下载语言模型（需要能访问 Google 服务）；也可以在设置里提前下载英语、日语、中文模型。
- **Google Cloud**：在 Google Cloud Console 启用 *Cloud Translation API*，创建 API 密钥后填入。
- **DeepL**：在 DeepL 账号页复制 API Key（免费版以 `:fx` 结尾，应用会自动选择免费接口）。
- **Azure**：创建 Translator 资源，填入密钥和区域（如 `eastasia`）。
- **OpenAI 兼容**：点“快速填写”选择服务商，再填 API Key；模型名可改（例如 `deepseek-chat`、`qwen-plus`）。局域网里的 Ollama 也可以（`http://<电脑IP>:11434/v1`）。
- **Claude**：填入 Anthropic API Key，选择模型。默认 Opus 5.5 质量最好；整本书翻译时 Sonnet 5.5 / Haiku 5.5 更省钱。

设置页的“测试翻译”可以检查配置是否正确。API Key 只保存在手机本地。

## 云存储配置

最省事的办法：手机装好 **Google Drive / Dropbox 官方 App**，然后在书架“导入 → 本地文件”的系统文件选择器侧边栏里直接选它们里的文件，无需任何配置。

想在应用内直接浏览云盘，需要各自注册一次开发者应用：

### Google Drive

1. 打开 [Google Cloud Console](https://console.cloud.google.com/)，新建（或选择）一个项目。
2. “API 和服务 → 库”：启用 **Google Drive API**。
3. “OAuth 同意屏幕”：用户类型选“外部”，添加范围 `https://www.googleapis.com/auth/drive.readonly`，在“测试用户”里加上你自己的 Google 账号（个人使用保持“测试”状态即可）。
4. “凭据 → 创建凭据 → OAuth 客户端 ID”，应用类型选 **Android**：
   - 软件包名称：`com.ruhuiliu.ereader`
   - SHA-1 证书指纹：`B1:A2:74:C6:10:99:27:87:27:4D:7B:D6:6E:0B:69:98:C3:D1:19:FA`
     （这是仓库里 `app/debug.keystore` 的指纹，Actions 编译和本地编译的 APK 都用它签名。如果你换成自己的签名密钥，用 `keytool -list -v -keystore 你的.keystore` 查看指纹。）
5. 无需在代码里填任何 ID，装好 App 后在“导入 → Google Drive”授权即可。若提示 `DEVELOPER_ERROR`，通常是包名或 SHA-1 不匹配。

### Dropbox

1. 打开 [Dropbox App Console](https://www.dropbox.com/developers/apps)，Create app → *Scoped access* → *Full Dropbox*。
2. Permissions 页勾选 `files.metadata.read`、`files.content.read`、`account_info.read` 并 Submit。
3. Settings 页确认 “Allow public clients (Implicit Grant & PKCE)” 为 Allow，复制 **App key**。
4. 编译时提供 App key（任选其一）：
   - 本地编译：在项目根目录 `local.properties` 中加一行 `dropbox.appKey=你的AppKey`
   - GitHub Actions：在仓库 Settings → Secrets → Actions 新建 `DROPBOX_APP_KEY`
5. 重新编译安装后，在“导入 → Dropbox”点击登录。

## 编译

需要 JDK 17+ 和 Android SDK（compileSdk 36）。用 Android Studio 打开项目即可，或命令行：

```bash
./gradlew :app:assembleRelease      # 输出在 app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest    # 单元测试（EPUB 解析、TXT 编码与分章、翻译结果解析等）
node tools/reader-js-test/run.js    # 阅读引擎测试（需要 playwright，详见文件头注释）
```

Release 版默认用仓库内的调试密钥签名，方便个人安装。要公开发布请准备自己的密钥，在 `local.properties` 中设置 `release.storeFile`、`release.storePassword`、`release.keyAlias`、`release.keyPassword`。

## 项目结构

```
app/src/main/
├── assets/reader/            阅读引擎（WebView 内运行）：分页、竖排转横排、双语插入、锚点、搜索高亮、脚注
├── java/com/ruhuiliu/ereader/
│   ├── book/                 EPUB 解析（OPF / nav / NCX / 封面 / 竖排识别）、TXT 编码识别与分章、HTML
│   ├── data/                 Room 数据库（书籍、书签、翻译缓存）、DataStore 设置、导入
│   ├── translate/            翻译引擎（ML Kit / Google / DeepL / Azure / OpenAI 兼容 / Claude）与缓存
│   ├── cloud/                Google Drive（REST + Google 授权）、Dropbox（官方 SDK）
│   ├── reader/               阅读界面（Compose）+ WebView 桥接 + ViewModel
│   └── ui/                   书架、设置、云盘浏览
```

## 已知限制

- 暂不支持 PDF、MOBI / AZW3；带 DRM 的书无法打开。
- 固定版式（漫画、图片为主）的 EPUB 能显示，但无法翻译，也无法转为横排。
- ML Kit 的中文译文只有简体；选择繁体时请换用其他引擎。
- 竖排保持原样时，脚注弹窗等少数界面仍为横排。
