# ImgAI App 开发计划 v0

## 目标
Android 应用，后台定期对相册照片做：
1. 人脸聚类 → 按人物分目录存放
2. 重复照片识别 + 质量评估 → 保留最佳，差的放待确认目录
3. 提供界面浏览聚类结果和待确认照片

## 目标设备
- Vivo X200 Pro (Android 15, API 35)
- 最低支持 API 26 (Android 8.0)

---

## Phase 0 — 最小验证 (当前)
**目标：编译通过 + 运行不崩 + 基础权限申请 + 扫描相册显示照片数量**

- [x] 项目脚手架 (Gradle, Manifest, 资源文件)
- [x] MainActivity 显示统计信息
- [x] 申请 READ_MEDIA_IMAGES 权限
- [x] 扫描 MediaStore 统计照片数量
- [ ] Vivo X200 Pro 实机验证编译运行

## Phase 1 — 人脸检测 + 特征提取
- [ ] 集成 ML Kit Face Detection
- [ ] 集成 MobileFaceNet TFLite 模型 (112x112 → 192-dim)
- [ ] 人脸裁剪、对齐、resize
- [ ] Room DB 存储人脸特征向量
- [ ] 单张照片人脸检测测试通过

## Phase 2 — 聚类 + 分目录
- [ ] DBSCAN 聚类实现 (cosine distance, eps=0.4, minPts=2)
- [ ] 按 Person_N 创建目录，复制照片
- [ ] 聚类结果列表页 (封面 + 数量)
- [ ] 人物详情九宫格页
- [ ] 大图全屏查看

## Phase 3 — 重复识别 + 质量评估
- [ ] pHash 感知哈希实现
- [ ] 按拍摄时间分组 (同一天内视为可能重复)
- [ ] 质量评分：清晰度(拉普拉斯方差) + 亮度 + 对比度
- [ ] 综合(时间*0.3 + 质量*0.7)选最佳
- [ ] 非最佳照片移入 PendingReview/ (不删除)
- [ ] 待确认浏览页面

## Phase 4 — 后台调度 + 健壮性
- [ ] WorkManager 每6小时定期执行
- [ ] 增量处理 (只处理新照片)
- [ ] 前台服务通知显示进度
- [ ] Vivo 电池优化白名单引导
- [ ] 错误恢复 + 断点续传

## Phase 5 — 打磨
- [ ] Material 3 UI 完善
- [ ] 聚类手动合并/拆分
- [ ] 人物命名
- [ ] 导出/分享
- [ ] 性能优化 (批处理, 内存管理)

---

## 技术栈
| 模块 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose / ViewBinding |
| 人脸检测 | ML Kit Face Detection 16.1.7 |
| 人脸特征 | MobileFaceNet TFLite (192-dim) |
| 聚类 | DBSCAN (cosine distance) |
| 重复检测 | pHash 感知哈希 |
| 质量评估 | Laplacian variance + 亮度/对比度 |
| 数据库 | Room |
| 后台调度 | WorkManager |
| 图片加载 | Coil |

## 目录结构规划
```
app/src/main/java/com/imgai/app/
├── MainActivity.kt          # 主界面
├── ImgAIApp.kt              # Application
├── ui/
│   ├── ClusterListActivity.kt    # 聚类结果列表
│   ├── ClusterDetailActivity.kt  # 人物照片网格
│   ├── ReviewActivity.kt         # 待确认照片
│   └── ImageViewerActivity.kt    # 大图查看
├── data/
│   ├── AppDatabase.kt
│   ├── ImageDao.kt
│   ├── FaceDao.kt
│   └── Entities.kt
├── worker/
│   └── ClusterWorker.kt     # WorkManager 后台任务
├── detect/
│   ├── FaceDetector.kt       # ML Kit 封装
│   └── EmbeddingExtractor.kt # TFLite 特征提取
├── cluster/
│   └── DBSCAN.kt
├── dedup/
│   ├── PHash.kt
│   └── QualityAssessor.kt
└── util/
    └── MediaStoreHelper.kt
```

## 输出目录结构 (用户手机上)
```
Pictures/ImgAI/
├── Person_1/          # 按人脸聚类
├── Person_2/
├── Person_3/
├── PendingReview/     # 重复/低质量待确认
└── .imgai_meta/       # 处理记录
```
