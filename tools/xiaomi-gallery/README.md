# 小米相册时间索引修复

适用于照片 EXIF 已正确、Android 媒体库已正确，但小米相册仍显示旧日期的情况。已在小米相册 `5.4.2.10-0907-cn`、HyperOS `OS4.0.0.8.XOCCNXM` 上实机验证。

小米相册另有 `/data/user/0/com.miui.gallery/databases/gallery.db`。照片扫描后，`realDateModified` 可以更新，`dateTaken`、`mixedDateTime`、`dateModified` 却保留旧值。普通应用核验 MediaStore 成功，不能说明小米相册已更新。

这个工具使用手机现有的 root 权限，复用已安装的 `local.capturetime` 应用的 `CaptureTimeParser`，通过 Android 自带的 SQLite 和 EXIF API 处理相册记录。无需安装 sqlite3 或常驻模块。

v1.5.3 也可直接从应用首页进入“小米相册时间修复 · Root”，选择统一两种排序时间或只修拍摄时间。界面与这个脚本共用 `app/src/main/java/local/capturetime/gallery/GalleryTimeRepair.java`，应用内备份可导出 ZIP。

## 使用

电脑需要 Python 3.10+、JDK 17、ADB、Android SDK Platform 35 / Build Tools 35.0.0；手机需要已安装本项目的应用，并允许 ADB shell 使用 root。先在项目根目录运行 `gradlew :app:testDebugUnitTest`，生成编译依赖。

```powershell
# 生成清单：会备份数据库，不修改照片或相册记录。
python tools/xiaomi-gallery/repair.py --serial "你的ADB序列号"

# 只修拍摄时间的清单：保留添加排序时间。
python tools/xiaomi-gallery/repair.py --serial "你的ADB序列号" --capture-only

# 查看输出的 plan.json 后，应用这份清单。
python tools/xiaomi-gallery/repair.py --serial "你的ADB序列号" --apply-plan "<输出目录>/plan.json"

# 再次核验同一批记录和数据库完整性。
python tools/xiaomi-gallery/repair.py --serial "你的ADB序列号" --verify-plan "<输出目录>/plan.json"

# 在独立测试数据库中检查事务回滚和“只修拍摄时间”保留添加时间。
python tools/xiaomi-gallery/repair.py --serial "你的ADB序列号" --check
```

如未设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，工具从 PATH 中 adb 的位置推断 SDK；也可指定 `--sdk`。`--check` 需要图库中存在一个满足三项时间一致、相册记录不同的条目；测试只修改单独的测试数据库。

## 修复边界与核验

- 清单仅选择文件名时间、EXIF 原始时间、文件修改时间一致到秒的本地照片；文件路径、文件名、同步状态也必须满足检查。
- 沿用应用设置中“忽略误差”的阈值，按所选模式检查相册排序时间，仅修复超过该误差的条目。默认模式把添加排序对齐可靠拍摄时间；`--capture-only` 生成的清单只修拍摄排序并保留添加排序。无可靠文件名、来源冲突、视频和仅在云端的照片不自动处理。
- 操作前暂停相册，保存 `gallery.db`、首页缓存库和辅助库的完整备份及 WAL，并核验手机与电脑备份的 SHA-256。
- 在一个事务中核对每条记录的旧值并重读文件依据，再更新 `dateTaken`、`mixedDateTime`、`exifDateTime`；默认模式还更新 `dateModified`，只修拍摄时间时则核验它保持原值。任一条记录变化，整批回滚，需重新生成清单。
- 修复后重读全部条目，检查数据库完整性；照片原文件的 SHA-256 必须保持不变。恢复数据库文件权限并重启相册，再核验一次。
- 所有备份、清单和哈希保存在项目的 `local-backups/xiaomi-gallery-日期时间/`。这些是私有照片元数据，不进入 Git，也不会被 Gradle clean 清理。

工具修复的是手机端的相册索引，不直接修改云端元数据。已修复记录经相册重启验证保持正确；后续云恢复可能新增其他旧时间条目，可重新生成清单并处理。它不会作为后台服务持续改写相册。

数据库备份是审计和紧急恢复材料。云同步继续运行后，不应直接用旧数据库覆盖整库，否则会丢失备份之后的相册状态。需要撤销时应根据保存清单逐条校验并恢复对应时间字段。
