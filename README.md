# 拍摄时间修正

仅供本机侧载使用的 Android 工具。应用扫描共享存储中的 JPEG、HEIC/HEIF 和 PNG，按照设置中选择的依据字段计算目标时间，并在用户明确确认后写入所选 EXIF 时间字段或文件修改时间。

应用不联网、不上传数据、不改文件名、不删除照片、不直接更新 MediaStore `DATE_TAKEN` 或 `DATE_ADDED`，也不访问小米图库的私有数据库或接口。Manifest 未声明 `INTERNET` 权限。

## 环境

- Android 11（API 30）或更高版本
- Android Studio Ladybug 或更新版本
- Android SDK Platform 35
- JDK 17（Android Studio 自带的 JBR 即可）
- 小米 HyperOS 手机，系统时区设为 `Asia/Shanghai`（UTC+08:00）

## Android Studio 构建

1. 在 Android Studio 中选择 **Open**，打开本 README 所在的 `capture-time-app` 目录。
2. 等待 Gradle Sync 完成。首次构建需要 Android Studio 下载 Gradle、Android Gradle Plugin、Kotlin 插件和 `androidx.exifinterface`。
3. 选择 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
4. Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

也可在已配置 JDK 17 和 Android SDK 的终端执行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

依赖仅来自 Google Maven/Maven Central：Android Gradle Plugin、Kotlin Gradle Plugin、`androidx.exifinterface` 和仅用于本地测试的 JUnit。应用运行时不使用任何云服务或网络 SDK。

## GitHub 自动化

- 对 `main` 的推送和所有拉取请求会运行单元测试、构建 Debug APK，并将 APK 作为 Actions 构件保留。
- 推送形如 `v1.0.0` 的版本标签会构建未签名 Release APK、生成 SHA-256 校验文件，并创建带 GitHub 自动生成发布说明的 Release。

## 安装

1. 将 `app-debug.apk` 传到手机本地。
2. 在系统文件管理器中打开 APK。
3. 按 HyperOS 提示允许该来源安装未知应用。
4. 完成安装后打开“拍摄时间修正”。

无需 root，不需要 ADB shell。发布签名不是本地侧载的必要条件；如需长期保留同一安装，可在 Android Studio 中使用自己的签名生成 APK。

## 授予权限

1. 首次打开应用时，在授权提示中点击“前往应用设置”，也可随时点击首页右上角设置按钮。
2. 在设置页的“照片访问”卡片中点击授权按钮，允许照片读取权限。
3. 随后在系统设置中打开“允许访问所有文件”。
4. 返回应用，确认页面显示“权限状态：已授予”。

拒绝或撤销 `MANAGE_EXTERNAL_STORAGE` 后，扫描和所有写入入口均禁用。每张照片真正写入前还会再次检查权限。

## 撤销权限

可使用任一方式：

- 系统设置 > 应用 > 特殊应用权限 > 所有文件访问权限 > 拍摄时间修正 > 关闭。
- 系统设置 > 应用管理 > 拍摄时间修正 > 权限，撤销照片读取权限。
- 卸载应用。

撤销权限不会删除已有会话备份和日志。

## 使用流程

1. 在首页选择一种只读入口：“自定义导入”会打开 Android 系统相册并支持多选本机照片；“全局扫描”会检查全部受支持的相册目录。两种入口都不会自动写入。
2. 查看每项的路径、当前拍摄时间、添加时间、文件名时间、目标时间、格式和安全状态。
3. 选择一张 JPEG 候选，点击“确认单张试运行”，阅读确认对话框后再次确认。
4. 只有 JPEG 完整通过备份、写入、EXIF 重读、媒体扫描和 MediaStore 精确验证后，批量按钮才会启用。
5. 点击批量按钮查看预计处理数、跳过数和风险，再次确认后才逐张执行。
6. HEIC/HEIF 和 PNG 默认不进入批量。对应格式必须先在当前预览中完成单张试运行；解锁仅在内存中有效，重新扫描或重启应用后失效。

应用未获得完整照片访问权限时，每次冷启动都会显示授权说明；授权与权限状态统一位于设置页。扫描快照保存在应用私有本机空间 `files/last-scan.json`，下次打开应用会先显示上次快照；需要获取最新文件状态时可重新从相册导入或执行全局扫描。列表只显示候选项，并使用 `RecyclerView` 虚拟化滚动，不会为全部照片创建界面控件。

自定义导入使用 Android Photo Picker / 系统图库选择器，不会打开文件管理器。由于应用需要原地修改并备份照片，云端照片或无法解析为本机 MediaStore 文件的项目会明确跳过，不会复制后修改副本。

主页右上角“设置”可以配置完整时间规则：从所选依据字段中取最早或最晚时间，并选择要写入的目标字段。依据字段包括当前拍摄时间、三个 EXIF 时间、MediaStore 拍摄/添加时间、文件名时间和文件修改时间；可修改字段包括三个 EXIF 时间与文件修改时间。MediaStore 字段仅供读取和写入后核验，不直接修改。

设置页还可配置忽略误差，分别输入天、时、分、秒。目标时间与某个已存在的目标字段差值不超过误差时，该字段不修改；缺失字段仍会补写。至少需要选择一个依据字段和一个修改字段。保存后返回主页会按新规则重新扫描。默认规则与旧版本一致：取当前拍摄时间、MediaStore 添加时间、文件名时间中的最早值，写入三个 EXIF 时间和文件修改时间。

设置页的“清除备份”只处理名称以 `capture-time-app-` 开头的会话目录，并会在永久删除前显示目录数量和大致大小并要求二次确认。它会同时删除该会话中的原始照片备份和日志，但不会删除 `/sdcard/.temp` 下的其他文件或目录。清除后无法依靠这些备份恢复照片。

应用只识别文件名中的：

```text
YYYY-MM-DD-HH-MM-SS
YYYYMMDD_HHMMSS
YYYYMMDD-HHMMSS
```

无时区的文件名和 EXIF 均按 `Asia/Shanghai` 严格解析。非法日期会忽略；选择文件名作为依据时，一个文件名中出现多个不同有效时间会跳过。

## 扫描范围与排除项

扫描：

```text
/sdcard/DCIM
/sdcard/Pictures
/sdcard/Download/MiShare
```

`DCIM` 大小写不敏感并去重。应用不跟随符号链接。路径任一段为 `.globalTrash`、`.thumbnails` 或以 `.trashed-` 开头时均排除；`/sdcard/DCIM/.globalTrash` 有额外硬保护。应用不会扫描视频，也不会读取、清理或删除 `/sdcard/.temp` 的既有内容。

## 安全链路

每次单张或批量执行都会创建全新的目录：

```text
/sdcard/.temp/capture-time-app-YYYYMMDD-HHMMSS/
```

每张照片按相对原路径备份。应用要求备份和原文件长度及 SHA-256 完全一致，才使用 `ExifInterface` 写入用户勾选的一个或多个字段：

```text
DateTimeOriginal
DateTimeDigitized
DateTime
```

写入值格式为 `yyyy:MM:dd HH:mm:ss`。应用不解码、重编码或压缩像素。写入后立即重读所选标签并核验所选文件修改时间，再调用公开的 `MediaScannerConnection.scanFile()`。MediaStore 最多等待 15 秒；修改 `DateTimeOriginal` 时要求 `DATE_TAKEN` 精确等于目标整秒，否则要求它保持原值；操作前后的 `DATE_ADDED` 秒值始终必须完全一致。

缺少可查询 `DATE_ADDED` 的文件会跳过，因为应用无法安全证明添加时间未变化。应用从不直接更新 MediaStore 时间列。

任何写入后错误都会立即从本会话备份覆盖恢复原文件，校验恢复文件与备份 SHA-256 一致，重新扫描，并核验原 EXIF、原 `DATE_TAKEN` 和原 `DATE_ADDED`。没有无限重试。

## 日志

每个会话目录包含：

- `planned.tsv`：该次执行采用的完整扫描快照、候选与跳过原因。
- `changed.tsv`：成功修改及 EXIF、MediaStore 核验结果。
- `skipped.tsv`：未修改项目及原因。
- `restored.tsv`：写入后失败并进入恢复流程的项目、恢复核验结果。
- `summary.json`：扫描、候选、成功、跳过、恢复、失败计数和会话路径。

日志为 UTF-8。首页“查看最近会话日志”可查看摘要和目录；也可用系统文件管理器打开该目录。

## 手工恢复

正常失败会自动恢复。若手机断电、系统强杀进程或存储故障导致自动恢复没有完成：

1. 立即停止使用应用，不要再次对同一照片执行批量操作。
2. 打开最近的会话目录，根据 `restored.tsv`、`changed.tsv` 和 `summary.json` 找到原路径与 `backup_path`。
3. 备份目录保持了原共享存储相对路径，例如会话中的 `DCIM/Camera/IMG_0001.jpg` 对应 `/sdcard/DCIM/Camera/IMG_0001.jpg`。
4. 先将当前原路径文件另存到其他位置，再将会话备份复制回原路径。不要改名。
5. 在系统设置中重启媒体存储相关服务或重启手机，让系统重新扫描；不要手工编辑 MediaStore 数据库。
6. 比较恢复文件和会话备份的 SHA-256；只有完全一致才视为文件内容恢复。

应用从不自动删除会话目录。确认无需恢复后，用户可自行归档或删除特定的 `capture-time-app-*` 目录；不要让清理工具误删尚需恢复的备份。

## 平台限制

- 小米图库是否立即刷新由 HyperOS 媒体扫描器决定；应用以公开 MediaStore 的精确核验为成功条件。
- HEIC/HEIF、PNG 的可写能力取决于设备、系统版本和具体文件结构，因此必须逐格式完成当前预览的真实单张测试。
- 系统媒体提供程序若自行改变 `DATE_ADDED`，应用会判定失败并恢复照片内容，但不会违反约束去写回该数据库列。
- 应用只能防护自身进程内可检测的错误。断电和底层存储损坏无法做到绝对事务原子性，因此原始备份会永久保留，直到用户自行处理。
