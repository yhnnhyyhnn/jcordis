# 发布手册（Maven Central）

> 记录 jcordis 发布到 Maven Central 的完整流程与踩坑约束。**不含任何凭据**——密钥口令、token 一律通过 `settings.xml` 注入（见下文），切勿写入仓库。

当前坐标：`io.github.yhnnhyyhnn:jcordis-{core,loader,cli,maven-plugin,all}:<version>`

---

## 一、一次性准备

### 1. 命名空间（namespace）

使用 `io.github.<github-user>`：在 <https://central.sonatype.com> 用 GitHub 账号登录后自动完成验证，**无需自有域名与 DNS 记录**（`io.jcordis` 需要拥有 `jcordis.io` 并配置 TXT 验证，因此被放弃）。

### 2. GPG 密钥

```bash
gpg --full-generate-key          # RSA 4096，用于签名
gpg --list-secret-keys --keyid-format LONG   # 记录 KEY_ID
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # 上传公钥（Central 校验用）
```

### 3. `settings.xml` 凭据（`~/.m2/settings.xml` 或 Maven 安装目录 `conf/settings.xml`）

```xml
<servers>
  <!-- Central Portal user token：username = token 的 user 部分 -->
  <server>
    <id>central</id>
    <username>...</username>
    <password>...</password>
  </server>
  <!-- GPG 口令（避免交互式输入、避免写入 pom） -->
  <server>
    <id>jcordis-gpg</id>
    <passphrase>...</passphrase>
  </server>
</servers>
```

pom 的 `release` profile 通过 `publishingServerId=central` 与 `passphraseServerId=jcordis-gpg` 引用上述两项。

**安全**：token 可在 Central 页面随时重新生成；GPG 私钥务必离线备份（丢失后已发布版本的签名无法复现）。发布完成后如怀疑泄露，轮换 token 并考虑更换密钥。

---

## 二、发布流程

### 0. 前置检查

```bash
mvn -Pformat spotless:check          # 格式
mvn clean verify                     # 全量测试（含 IT）
grep -rn "SNAPSHOT" pom.xml */pom.xml | grep -v dependency   # 确认版本号已定为正式版
```

工作区必须干净（无未提交改动），版本号已由 `X.Y.Z-SNAPSHOT` 改为 `X.Y.Z`。

### 1. parent 先发布（**必须**）

```bash
mvn -Prelease deploy -N -DskipTests \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -ntp
```

Central 校验子模块 pom 时需要已能看到 parent，否则继承元数据（Project URL / License / SCM / Developers）解析失败。

### 2. 逐模块发布

```bash
mvn -Prelease deploy -pl :jcordis-core,:jcordis-loader,:jcordis-cli,:jcordis-maven-plugin,:jcordis-all \
  -DskipTests \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -ntp
```

（首次发布建议逐模块单独执行，便于定位失败项；顺序上 core/loader 在前。）

### 3. Central Portal 手动 Publish（**当前必须**）

`central-publishing-maven-plugin:0.6.0` **没有命令行 publish goal**，且 `autoPublish` 实测未自动触发——部署完成后状态为 `validated`，需要人工操作：

1. 打开 <https://central.sonatype.com> → **Publishing** → **Deployments**
2. 找到对应 deployment（`deploymentId` 会打印在构建日志中）
3. 点击 **Publish**，等待状态变为 `PUBLISHED`

对每个模块重复（parent 与各模块是各自独立的 deployment）。

### 4. 发布后收尾

```bash
git tag -a vX.Y.Z -m "jcordis X.Y.Z — 发布至 Maven Central"
git push origin main && git push origin vX.Y.Z

# 版本回退到下一个开发版本
#   pom.xml（parent + 各模块）X.Y.Z → X.Y.(Z+1)-SNAPSHOT
#   jcordis-all 的 JcordisAll.VERSION 同步
mvn clean verify
git commit -am "chore: 版本回退至 X.Y.(Z+1)-SNAPSHOT"
```

更新 `CHANGELOG.md` 增加该版本条目。

---

## 三、已知校验规则（Central）

| 规则 | 说明 |
|---|---|
| 必需元数据 | Project URL、License、SCM URL、Developers 缺一不可（继承自 parent） |
| 依赖版本 | **所有**依赖（含 test scope）必须有显式版本——BOM 管理的依赖来自 parent 的 `dependencyManagement`，故 parent 必须先发布 |
| sources / javadoc | 每个模块都要产出 `-sources.jar` 与 `-javadoc.jar`；**空源码模块**（如聚合模块 `jcordis-all`）需放一个标记类 |
| 签名 | 所有工件（含 pom）需 GPG 签名 |
| 不可变 | 同一 `groupId:artifactId:version` 不可重复发布，发布前务必确认版本号 |

---

## 四、故障排查

| 症状 | 原因 / 处理 |
|---|---|
| `Project URL is not defined` / `License information is missing` / `SCM URL is not defined` / `Developers information is missing` | parent 尚未发布或版本不匹配，先发布 parent |
| `Could not find artifact ...:jcordis-parent:pom:X.Y.Z` | 本地仓库缺 parent；先 `mvn -N install` 或发布 parent |
| 校验器报依赖缺版本 | 依赖未在 `dependencyManagement` 中；补版本或改用 parent 的 BOM |
| 空模块报无 sources/javadoc | 加标记类 |
| 代理连接失败 | 补 `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890`（GitHub / Central / GCS 均需） |
| 部署停在 `validated` | 正常——到 Portal 手动 Publish |
| Windows 上临时目录删除失败 | 见 loader 的 jar 句柄管理；`Loader.loadJar/replaceJar/unload` 已串行化 |

---

## 五、本地/离线分发（不走 Central）

```bash
# 安装到本地仓库
mvn clean install -DskipTests

# 打 tag 后用独立工作区构建（避免污染开发工作区）
git worktree add /tmp/jcordis-<version> v<version>
cd /tmp/jcordis-<version> && mvn clean install
```

依赖方（如 majo）在 Central 同步完成前可先指向本地仓库。
