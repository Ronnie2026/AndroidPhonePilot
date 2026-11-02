# Tencent Mars XLog 1.2.6

- 上游：https://github.com/Tencent/mars
- 坐标：com.tencent.mars:mars-xlog:1.2.6
- 本地依赖：app/libs/mars-xlog-1.2.6.aar（未经修改）
- 下载来源（JCenter 镜像）：https://maven.aliyun.com/repository/jcenter/com/tencent/mars/mars-xlog/1.2.6/mars-xlog-1.2.6.aar
- SHA-256：cfc443bfc1b84709384d8a650217dc691aee80347bc848f01ffb36ce0e54a667
- 许可：本目录 LICENSE 为腾讯上游许可及第三方声明，来源 https://github.com/Tencent/mars/blob/master/LICENSE 。原有版权声明保留。

因旧版坐标不在 Maven Central 中，本项目固定归档 AAR，避免构建依赖已下线的 JCenter。它包含 arm64-v8a、armeabi-v7a 的 native 库，以及 libc++_shared.so；不包含 x86/x86_64。本项目未修改其实现。Android 15 ARM64、4KB 页模拟器已验证加载和写入；16KB 页设备及其他真机兼容性尚未验证。

XLog 为本地日志组件，不等同于腾讯云日志服务，本项目没有日志上传接口。查询索引存于应用私有 SQLite 数据库；归档为原生 .xlog 格式。删除记录会关闭并重建本项目独占的 XLog 文件/缓存目录。
