package com.myhope.sync.myhope;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.myhope.sync.model.ItemType;
import com.myhope.sync.model.SnapshotItem;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * buildOps 判定逻辑的集成验证（连真实服务端，只读）。
 *
 * <p>验证用户最终敲定的同步语义：判断新增/更新 = method + url（不看 id）；陈旧实体直接 update、
 * 绝不新建；选定新目录则 create；op 中绝不出现 move。</p>
 *
 * <p>本测试只拉取真实快照做基准 + 跑 buildOps 生成 op 并断言，<b>不向服务端 push 任何 op</b>（无副作用）。</p>
 *
 * <p>凭据通过环境变量注入，缺失时测试自动跳过（不失败），避免真实 token 硬编码入库：
 * <ul>
 *   <li>MH_BASE_URL（默认 http://localhost:3462/pr-api/api）</li>
 *   <li>MH_TOKEN（X-API-Token）</li>
 *   <li>MH_INSTANCE_ID（X-MyHope-Instance-ID）</li>
 *   <li>MH_ACCESS_INSTANCE_ID</li>
 *   <li>MH_USER_APP_ID</li>
 *   <li>MH_WORKSPACE_ID</li>
 *   <li>MH_BRANCH_ID</li>
 *   <li>MH_AUTHOR_ID</li>
 *   <li>MH_BASE_SEQ（当前服务端水位，默认 0）</li>
 * </ul>
 * 运行示例：
 * <pre>
 *   MH_TOKEN=... MH_INSTANCE_ID=... ... gradle test --tests '*BuildOpsSyncVerificationTest'
 * </pre>
 */
public class BuildOpsSyncVerificationTest {

    private final MyHopeDataConverter conv = new MyHopeDataConverter();

    // 与服务端一致的 gson：需注册 ItemTypeJsonAdapter 才能反序列化 type 字段
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(ItemType.class, new ItemTypeJsonAdapter())
            .create();

    private String baseUrl;
    private String token;
    private String instance;
    private String accessInstance;
    private String userApp;
    private String workspace;
    private String branch;
    private String author;
    private int baseSeq;

    @Before
    public void setUp() {
        baseUrl = env("MH_BASE_URL", "http://localhost:3462/pr-api/api");
        token = System.getenv("MH_TOKEN");
        instance = System.getenv("MH_INSTANCE_ID");
        accessInstance = System.getenv("MH_ACCESS_INSTANCE_ID");
        userApp = System.getenv("MH_USER_APP_ID");
        workspace = System.getenv("MH_WORKSPACE_ID");
        branch = System.getenv("MH_BRANCH_ID");
        author = System.getenv("MH_AUTHOR_ID");
        baseSeq = parseInt(env("MH_BASE_SEQ", "0"));

        // 缺真实凭据时跳过（本测试是连真实服务端的集成验证，无凭据无法运行）
        Assume.assumeTrue("缺少 MH_TOKEN 等真实凭据，跳过 buildOps 集成验证测试",
                token != null && !token.isEmpty()
                && accessInstance != null && userApp != null
                && workspace != null && branch != null);
        if (author == null) author = accessInstance;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 夹具 ====================

    private static SnapshotItem col(String id, String parent, String title) {
        return new SnapshotItem(id, parent, ItemType.COLLECTION, title, null, null,
                Collections.emptyList(), Collections.emptyList(), null, null, null, null, null,
                Collections.emptyList());
    }

    private static SnapshotItem req(String id, String parent, String method, String endpoint) {
        return new SnapshotItem(id, parent, ItemType.REQUEST, method + " " + endpoint, method, endpoint,
                Collections.emptyList(), Collections.emptyList(), null, null, null, null, null,
                Collections.emptyList());
    }

    /** 拉真实服务端快照，返回 items（只读）。 */
    private List<SnapshotItem> fetchSnapshot() throws Exception {
        String url = baseUrl + "/branches/" + branch
                + "/snapshot?access_instance_id=" + accessInstance
                + "&user_app_id=" + userApp + "&workspace_id=" + workspace;
        HttpClient hc = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .header("X-API-Token", token).header("X-MyHope-Instance-ID", instance)
                .timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> resp = hc.send(r, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("snapshot HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("data");
        List<SnapshotItem> items = new ArrayList<>();
        for (JsonElement el : arr) {
            items.add(gson.fromJson(el, SnapshotItem.class));
        }
        return items;
    }

    private static long countByType(List<Map<String, Object>> ops, String prefix) {
        return ops.stream().filter(o -> ((String) o.get("op_type")).startsWith(prefix)).count();
    }

    // ==================== 场景 ====================

    /**
     * 场景1：根级（不选目录）重推同一数据，服务端已有 → 应全部 update（用服务端 id），无 create、无 move。
     */
    @Test
    public void rootResync_updatesExisting_noCreateOrMove() throws Exception {
        List<SnapshotItem> serverReal = fetchSnapshot();
        Assert.assertFalse("服务端快照应为空才跳过，但不应为空", serverReal.isEmpty());

        // 本地 items：模块集合 -> controller 集合 -> request（模拟 toSnapshotItems 产物）
        List<SnapshotItem> localRoot = Arrays.asList(
                col("hc_LOCALMOD_root", null, "dlyx-b-database-analysis"),
                col("hc_LOCALCOL_root", "hc_LOCALMOD_root", "数据库连接管理接口"),
                req("hr_LOCALREQ_root", "hc_LOCALCOL_root", "POST", "/dbConnection/manage/list")
        );
        List<Map<String, Object>> ops = conv.buildOps(localRoot, serverReal, baseSeq, branch, workspace, author, null);

        Assert.assertEquals("根级重推不应产生 create", 0, countByType(ops, "create_"));
        Assert.assertEquals("根级重推不应产生 move", 0, countByType(ops, "move_"));

        // 服务端应确实存在 POST:/dbConnection/manage/list，更新要落在其真实 id 上（字段级 update_*）。
        // 服务端可能存在历史重复数据（同 method:endpoint 多条），故断言"命中任一真实 id 的 update"。
        List<String> serverReqIds = serverReal.stream()
                .filter(i -> i.getType() == ItemType.REQUEST
                        && "POST".equals(i.getMethod())
                        && "/dbConnection/manage/list".equals(i.getEndpoint()))
                .map(SnapshotItem::getId)
                .collect(java.util.stream.Collectors.toList());
        Assert.assertFalse("服务端应存在 POST:/dbConnection/manage/list 端点，请先用插件同步一次", serverReqIds.isEmpty());
        boolean updatedOnServerId = ops.stream().anyMatch(o ->
                serverReqIds.contains(o.get("entity_id")) && ((String) o.get("op_type")).startsWith("update_"));
        Assert.assertTrue("根级重推应对已存在端点用服务端 id 发字段级 update，期望命中任一 server id=" + serverReqIds,
                updatedOnServerId);
    }

    /**
     * 场景2：选定新目录（目标目录下无数据）→ 应全部 create，无 move。
     */
    @Test
    public void selectedDirEmpty_allCreate_noMove() {
        String newDir = "hc_NEWDIR_TEST";
        List<SnapshotItem> serverEmptyDir = Collections.singletonList(col(newDir, null, "新目录"));
        List<SnapshotItem> localDir = Arrays.asList(
                col("hc_LOCALMOD_dir", newDir, "dlyx-b-database-analysis"),
                col("hc_LOCALCOL_dir", "hc_LOCALMOD_dir", "数据库连接管理接口"),
                req("hr_LOCALREQ_dir", "hc_LOCALCOL_dir", "POST", "/dbConnection/manage/list")
        );
        List<Map<String, Object>> ops = conv.buildOps(localDir, serverEmptyDir, baseSeq, branch, workspace, author, newDir);

        Assert.assertEquals("选新目录应全部为 create（3 个）", 3, countByType(ops, "create_"));
        Assert.assertEquals("选新目录不应产生 move", 0, countByType(ops, "move_"));
    }

    /**
     * 场景3：陈旧实体（同 method+url、不同 id）→ 不新建、无 move；有内容差异时 update 用服务端旧 id。
     */
    @Test
    public void staleEntity_noCreate_updateServerId() {
        String newDir = "hc_NEWDIR_TEST";
        String oldMod = "hc_OLDMODDDDDDDDDDDDDDDD";
        String oldCol = "hc_OLDCOLDDDDDDDDDDDDDDD";
        String oldReq = "hr_OLDDDDDDDDDDDDDDDDD";
        // 目标目录 NEW_DIR 下已有完整同标题数据，但 request 是旧 id
        List<SnapshotItem> serverStale = Arrays.asList(
                col(oldMod, newDir, "dlyx-b-database-analysis"),
                col(oldCol, oldMod, "数据库连接管理接口"),
                req(oldReq, oldCol, "POST", "/dbConnection/manage/list")
        );
        List<Map<String, Object>> ops = conv.buildOps(
                Arrays.asList(
                        col("hc_LOCALMOD_dir", newDir, "dlyx-b-database-analysis"),
                        col("hc_LOCALCOL_dir", "hc_LOCALMOD_dir", "数据库连接管理接口"),
                        req("hr_LOCALREQ_dir", "hc_LOCALCOL_dir", "POST", "/dbConnection/manage/list")
                ),
                serverStale, baseSeq, branch, workspace, author, newDir);

        // 无差异：不应新建任何实体，也不 move
        Assert.assertEquals("陈旧实体(无差异)不应产生 create", 0, countByType(ops, "create_"));
        Assert.assertEquals("陈旧实体不应产生 move", 0, countByType(ops, "move_"));

        // 有 title 差异：应 update 服务端旧 id，不新建、不 move
        SnapshotItem localReqDiff = new SnapshotItem("hr_LOCALREQ_dir", "hc_LOCALCOL_dir", ItemType.REQUEST,
                "数据库连接-列表-改名", "POST", "/dbConnection/manage/list",
                Collections.emptyList(), Collections.emptyList(), null, null, null, null, null,
                Collections.emptyList());
        List<Map<String, Object>> opsDiff = conv.buildOps(Arrays.asList(
                col("hc_LOCALMOD_dir", newDir, "dlyx-b-database-analysis"),
                col("hc_LOCALCOL_dir", "hc_LOCALMOD_dir", "数据库连接管理接口"),
                localReqDiff), serverStale, baseSeq, branch, workspace, author, newDir);

        Assert.assertEquals("带差异陈旧实体仍不应新建", 0, countByType(opsDiff, "create_"));
        Assert.assertEquals("带差异陈旧实体不应 move", 0, countByType(opsDiff, "move_"));
        boolean updatedOldId = opsDiff.stream().anyMatch(o ->
                oldReq.equals(o.get("entity_id")) && ((String) o.get("op_type")).startsWith("update_"));
        Assert.assertTrue("带差异陈旧实体应 update 用服务端旧 id " + oldReq, updatedOldId);
    }
}
