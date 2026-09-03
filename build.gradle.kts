import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.myhope.sync"
            version = "2.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
    // 纯 JVM 的 buildOps 集成验证测试（连真实服务端，凭据走环境变量，缺则跳过）
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.myhope.sync"
            name = "MyHope Sync"
            version = "2.1.0"
        description = """
            <h3>English</h3>
            <p>Sync Spring Boot REST API endpoints to MyHope branch snapshots (myhope-pr-server).</p>
            <p><b>⚠️ Spring Boot (Java) only.</b></p>
            <p>Features:</p>
            <ul>
                <li>Parse @RestController and @Controller classes in your project</li>
                <li>Extract all HTTP endpoints (@RequestMapping, @GetMapping, @PostMapping, etc.)</li>
                <li>Parse path/query/body/header parameters with JSON skeleton preview</li>
                <li>Sync to a MyHope branch snapshot via X-API-Token + instance binding + full snapshot overwrite</li>
                <li>Sync status tracking: SYNCED (green), MODIFIED (blue), UNSYNCED (white)</li>
                <li>Multi-project/module support in a single IDEA window</li>
                <li>Selective sync with checkbox — search filters auto-select visible rows</li>
                <li>Automatic instance binding (access_instance_id / user_app_id)</li>
                <li>Adjustable table columns (show/hide, reorder, resize with session persistence)</li>
                <li>Bilingual UI (English / 中文) with instant switch</li>
            </ul>
            <hr>
            <h3>中文</h3>
            <p>将 Spring Boot REST API 端点同步到 MyHope 分支快照（myhope-pr-server）。</p>
            <p><b>⚠️ 仅支持 Spring Boot (Java) 项目。</b></p>
            <p>功能：</p>
            <ul>
                <li>扫描 @RestController / @Controller 类，提取所有 HTTP 端点</li>
                <li>解析路径/查询/请求体/请求头参数，展示 JSON 骨架预览</li>
                <li>通过 X-API-Token + 实例绑定 + 全量快照覆盖同步到 MyHope 分支</li>
                <li>同步状态追踪：🟢 已同步 / 🔵 已修改 / ⬜ 未同步</li>
                <li>单窗口多项目/多模块支持</li>
                <li>复选框选择性同步，搜索过滤自动勾选可见行</li>
                <li>自动实例绑定（access_instance_id / user_app_id）</li>
                <li>表格列显隐 / 排序 / 拖拽调整宽度，重启后保留</li>
                <li>中英文界面即时切换</li>
            </ul>
        """.trimIndent()
            changeNotes = """
            <ul>
                <li><b>2.1.0</b> 持久化作用域拆分: 工作空间/分支/同步状态/扫描缓存/列设置改为按项目隔离（存于项目 .idea/myhope-sync-project.xml）。老版本全局缓存不再生效，首次打开需重新配置工作空间/分支并刷新扫描。</li>
                <li><b>2.0.0</b> 全面改造: 从 Hoppscotch 同步转向 myhope-pr-server（X-API-Token + 实例绑定 + 分支快照同步）；包名/插件 id 更名 com.myhope.sync</li>
                <li><b>1.3.5</b> 新增: 搜索框后"仅已同步"过滤勾选框；状态栏悬停提示复制快捷键；精简 AGENTS.md</li>
                <li><b>1.3.4</b> Fix: 移除依赖 orderIndex（GraphQL 不暴露该字段），改用 title 确定性匹配以避免集合/请求重复创建；新增 25 项场景化集成测试覆盖 6 个模块（连接/版本/数据转换/同步编排/状态检测/持久化）</li>
                <li><b>1.3.0</b> 新增: 服务端版本检测；请求 JSON 前置 Zod 校验；请求 Schema 升级至 v17；修复 body 序列化兼容性</li>
                <li><b>1.2.4</b> Fix: 请求 body 序列化时 Gson 排除 null 字段导致 web 端 Zod 校验失败显示 Untitled</li>
                <li><b>1.2.3</b> 优化: 集合树查询改为一次 GraphQL 嵌套请求，消除 N+1 懒加载和 Thread.sleep(300)</li>
                <li><b>1.2.2</b> 优化: 同步状态检查新增 target 模式；集合选择器支持 target 路径显示</li>
                <li><b>1.2.1</b> Fix: 网络不通时区分「Token 过期」和「服务器不可达」; 移除设置中的帮助链接</li>
                <li><b>1.2.0</b> 新增: 物理列显隐; 标题列合并入路径列; 接口列显示 @Api tag; 列宽跨会话持久化; 列显隐恢复宽度; 支持以项目及类结构生成子集合</li>
                <li><b>1.1.0</b> 新增: 标题列; 支持 @ApiOperation 标题; 同步状态持久化 serverId</li>
                <li><b>1.0.0</b> Initial release</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
        vendor {
            name = "MyHope Sync"
            email = "luyanfeng001001@gmail.com"
        }
    }
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
        }
    }

    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN") ?: ""
        privateKey = System.getenv("PRIVATE_KEY") ?: ""
        password = System.getenv("PRIVATE_KEY_PASSWORD") ?: ""
    }
    publishing {
        token = System.getenv("JETBRAINS_TOKEN") ?: ""
        channels = listOf("default")
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

}
