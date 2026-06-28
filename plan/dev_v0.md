# ImgAI App 开发计划 v1

## 目标
Android 应用，后台定期对相册照片做智能分类管理：
1. **大类自动分类**：人物、风景、美食、文档等
2. **人物子聚类**：按人脸特征自动分人物目录
3. **重复照片识别 + 质量评估**：保留最佳，差的放待确认
4. **分目录存放**：按类别自动整理到 `Pictures/ImgAI/分类/`
5. **浏览 UI**：查看分类结果，一直可见的入口

## 目标设备
- Vivo X200 Pro (Android 15, API 35)
- 最低支持 API 26

---

## 已完成

### Phase 0 — 最小验证 ✅
- 编译运行 + 权限申请 + 相册扫描（5490张）

### Phase 1 — 人脸检测 + 特征提取 + 聚类 ✅
- ML Kit 人脸检测（精确模式）
- MobileFaceNet TFLite 特征提取（112x112 → 192-dim）
- DBSCAN 聚类（cosine distance, eps=0.4）
- Room 数据库
- 自动测试框架（am start + 文件日志）
- 测试结果：76张/7天 → 22人脸 → 2人物（Person_1: 18张, Person_2: 3张）

---

## 待开发

### Phase 2 — 主界面重构 + 手动触发聚类（当前）
**目标：用户能点按钮触发聚类，看到进度，完成后收到通知**

- [ ] 主界面改为底部导航（BottomNavigationView）
  - Tab 1: **首页**（触发聚类按钮 + 进度 + 统计概览）
  - Tab 2: **分类浏览**（各类别入口，常驻可见）
  - Tab 3: **待确认**（重复/低质量照片）
- [ ] 首页「聚类最近一周」按钮
  - 点击后显示 ProgressBar + 文字进度（"处理 23/76, 检测到 18 个人脸"）
  - 聚类过程中按钮禁用
  - 完成后弹 Toast + 飞书通知
- [ ] Service 回调进度到 UI（LiveData 或 BroadcastReceiver）
- [ ] 聚类结果写入 SQLite（已有 Room，扩展表结构）

### Phase 3 — 大类分类（人物/风景/美食/文档）
**目标：除人脸外，增加场景大类分类**

- [ ] 轻量图像分类模型（TensorFlow Lite + EfficientNet/LiteRT 或 ML Kit Image Labeling）
  - 人物（含人脸的照片自动归入）
  - 风景（户外、自然、城市）
  - 美食
  - 文档/截图
  - 其他
- [ ] 分类层次结构：
  ```
  Pictures/ImgAI/
  ├── 人物/
  │   ├── Person_1/
  │   ├── Person_2/
  │   └── 未识别人物/
  ├── 风景/
  ├── 美食/
  ├── 文档/
  ├── 其他/
  └── 待确认/  (重复/低质量)
  ```
- [ ] 与已有目录合并：增量聚类时，新照片的特征和已有簇比较
  - 匹配到已有簇 → 归入
  - 新人物/新类别 → 创建新目录
- [ ] 数据库表结构：
  - `categories` 表：id, name(人物/风景/美食...), parent_id
  - `clusters` 表：id, category_id, label(Person_1...), representative_path
  - `photo_categories` 表：photo_uri, category_id, cluster_id, quality_score, is_best

### Phase 4 — 重复识别 + 质量评估
- [ ] pHash 感知哈希（已实现 QualityAssessor）
- [ ] 按拍摄时间分组 + 同组 pHash 比对
- [ ] 质量评分：清晰度 + 亮度 + 对比度
- [ ] 综合(拍照时间*0.3 + 质量*0.7)选最佳
- [ ] 非最佳移入 `待确认/`（不删除）
- [ ] 待确认 Tab 浏览 + 手动保留/删除

### Phase 5 — 增量聚类 + 目录合并
- [ ] 增量处理：只处理新增照片
- [ ] 新人脸特征与已有聚类中心比较
  - cosine similarity > 0.6 → 归入已有 Person
  - 否则创建新 Person
- [ ] 新大类与已有分类比较
- [ ] 照片复制（不移动原件）到分类目录
- [ ] 断点续传：记录处理位置

### Phase 6 — 后台自动执行
- [ ] WorkManager 每6小时定期执行
- [ ] 前台服务通知显示进度
- [ ] Vivo 电池优化白名单引导
- [ ] 增量处理优化

### Phase 7 — 打磨
- [ ] 聚类结果手动合并/拆分人物
- [ ] 人物命名
- [ ] 大图全屏查看 + 左右滑动
- [ ] 性能优化

---

## 技术栈
| 模块 | 技术 |
|------|------|
| 语言 | Kotlin |
| UI | ViewBinding + BottomNavigationView + RecyclerView |
| 人脸检测 | ML Kit Face Detection 16.1.7 |
| 人脸特征 | MobileFaceNet TFLite (192-dim) |
| 场景分类 | ML Kit Image Labeling 或 MobileNetV3 TFLite |
| 聚类 | DBSCAN (cosine distance) |
| 重复检测 | pHash 感知哈希 |
| 数据库 | Room (SQLite) |
| 后台调度 | WorkManager |
| 图片加载 | Glide |

## 数据库设计

```sql
-- 照片记录
CREATE TABLE photos (
  uri TEXT PRIMARY KEY,
  date_taken INTEGER,
  category_id INTEGER,      -- 大类：人物/风景/美食...
  cluster_id INTEGER,       -- 子类：Person_1/Person_2...
  quality_score REAL,
  phash BIGINT,
  processed_at INTEGER,
  status TEXT DEFAULT 'normal'  -- normal/pending_review/best
);

-- 分类
CREATE TABLE categories (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,       -- 人物/风景/美食/文档/其他
  icon TEXT,
  sort_order INTEGER
);

-- 人脸聚类
CREATE TABLE face_clusters (
  id INTEGER PRIMARY KEY,
  label TEXT,               -- Person_1, Person_2...
  representative_uri TEXT,  -- 代表照片
  member_count INTEGER DEFAULT 0
);

-- 人脸特征
CREATE TABLE face_embeddings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  photo_uri TEXT,
  embedding TEXT,           -- 逗号分隔的192个float
  cluster_id INTEGER,
  face_rect TEXT
);

-- 重复组
CREATE TABLE duplicate_groups (
  id INTEGER PRIMARY KEY,
  best_uri TEXT,
  pending_uris TEXT,
  created_at INTEGER
);
```

## 输出目录结构
```
Pictures/ImgAI/
├── 人物/
│   ├── Person_1/
│   ├── Person_2/
│   └── 未识别人物/
├── 风景/
├── 美食/
├── 文档/
├── 其他/
└── 待确认/
```
