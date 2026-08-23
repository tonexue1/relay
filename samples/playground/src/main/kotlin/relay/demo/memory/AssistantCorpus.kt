package relay.demo.memory

import relay.memory.GRAPH_ASSISTANT
import relay.memory.TripleDraft

/**
 * Hand-written assistant graph for playground. No extractor.
 * Wave 1 = file, wave 2 = sets + other people, wave 3 = supersede + retract.
 */
object AssistantCorpus {

    data class Wave(val title: String, val hint: String, val drafts: List<TripleDraft>)

    data class Probe(val label: String, val prompt: String, val expect: String)

    private fun t(s: String, p: String, o: String, retract: Boolean = false) =
        TripleDraft(GRAPH_ASSISTANT, s, p, o, retract = retract)

    val waves: List<Wave> = listOf(
        Wave(
            title = "第1波 档案",
            hint = "闭集谓语铺一层：过敏、家人、猫、公司、待办、打算去美国",
            drafts = listOf(
                t("用户", "allergic_to", "花生"),
                t("用户", "likes", "美式"),
                t("用户", "dislikes", "香菜"),
                t("用户", "prefers", "地铁"),
                t("用户", "diet", "素食"),
                t("用户", "lives_in", "杭州"),
                t("用户", "work_location", "西溪"),
                t("用户", "born_in", "合肥"),
                t("用户", "works_at", "阿里"),
                t("用户", "works_as", "客户端"),
                t("用户", "work_years", "两年"),
                t("用户", "alumni_of", "浙大"),
                t("用户", "member_of", "篮球队"),
                t("用户", "skilled_in", "Kotlin"),
                t("用户", "knows_language", "英语"),
                t("用户", "colleague_of", "王磊"),
                t("用户", "friend_of", "李娜"),
                t("用户", "family_of", "外婆"),
                t("用户", "child_of", "妈妈"),
                t("用户", "child_of", "爸爸"),
                t("用户", "sibling_of", "姐姐"),
                t("用户", "has_pet", "猫"),
                t("猫", "named", "芝麻"),
                t("猫", "located_in", "杭州"),
                t("用户", "owns", "自行车"),
                t("用户", "takes", "钙片"),
                t("用户", "attends", "夜校"),
                t("用户", "plans", "美国"),
                t("用户", "has_task", "作业"),
                t("妈妈", "likes", "花生"),
                t("妈妈", "lives_in", "杭州"),
            ),
        ),
        Wave(
            title = "第2波 集合",
            hint = "集合边并存、别人的口味。美式和拿铁同时在。加了打算 离职，方便并到跳槽",
            drafts = listOf(
                t("用户", "likes", "拿铁"),
                t("用户", "likes", "火锅"),
                t("用户", "likes", "手冲"),
                t("用户", "likes", "米线"),
                t("用户", "dislikes", "内脏"),
                t("用户", "plans", "跳槽"),
                t("用户", "plans", "考研"),
                t("用户", "plans", "离职"),
                t("用户", "has_task", "报销"),
                t("用户", "friend_of", "阿秀"),
                t("用户", "colleague_of", "张伟"),
                t("用户", "owns", "相机"),
                t("用户", "attends", "瑜伽"),
                t("用户", "skilled_in", "JNI"),
                t("用户", "knows_language", "日语"),
                t("用户", "member_of", "读书会"),
                t("用户", "takes", "维生素"),
                t("用户", "spouse_of", "陈晨"),
                t("用户", "parent_of", "小宝"),
                t("姐姐", "lives_in", "北京"),
                t("姐姐", "likes", "香菜"),
                t("外婆", "likes", "豆浆"),
                t("李娜", "likes", "抹茶"),
                t("王磊", "likes", "围棋"),
                t("用户", "has_pet", "狗"),
                t("狗", "named", "豆豆"),
            ),
        ),
        Wave(
            title = "第3波 变更",
            hint = "功能边换对象；撤回美国。杭州/阿里/两年应从活图消失，跳槽还在",
            drafts = listOf(
                t("用户", "lives_in", "上海"),
                t("用户", "works_at", "字节"),
                t("用户", "works_as", "架构"),
                t("用户", "work_years", "三年"),
                t("用户", "work_location", "漕河泾"),
                t("用户", "diet", "清真"),
                t("用户", "plans", "美国", retract = true),
                t("用户", "plans", "买房"),
                t("用户", "has_task", "搬家"),
                t("静安", "located_in", "上海"),
                t("猫", "located_in", "上海"),
            ),
        ),
    )

    val probes: List<Probe> = listOf(
        Probe("花生", "花生", "过敏 花生"),
        Probe("妈妈", "妈妈", "妈妈 喜欢 花生"),
        Probe("作业", "作业", "待办 作业"),
        Probe("芝麻", "芝麻", "猫 名叫 芝麻"),
        Probe("美式", "美式", "喜欢 美式"),
        Probe("王磊", "王磊", "同事 王磊"),
        Probe("火锅", "火锅", "第2波后是喜欢 火锅，不是过敏提示"),
        Probe("上海", "上海", "第3波后用户住上海"),
        Probe("字节", "字节", "第3波后就职于字节"),
        Probe("工龄", "工龄", "第3波后是三年"),
        Probe("美国", "美国", "第3波后活图不应再有 打算 美国"),
        Probe("并节点", "把离职并到跳槽", "应调 memory_merge_nodes；活图只剩 打算 跳槽"),
        Probe("林晚", "林晚", "应空，小说节点不在助手图"),
    )

    /** Playground 对话 chip：说完自动抽取；下一轮问火锅应能垫到过敏。 */
    val talks: List<Probe> = listOf(
        Probe("过敏", "我花生过敏，火锅别放花生。", "说完自动进图：用户 过敏 花生"),
        Probe("我妈", "我妈爱吃花生。", "应有 妈妈 喜欢 花生、用户 child_of 妈妈"),
        Probe("离职", "我打算离职，先歇一阵。", "应有 plans 跳槽、plans 休息；做梦或并成规范名"),
        Probe("签证", "不去美国了，签证没过。", "若图里有打算 美国，应 retract"),
        Probe("火锅", "今晚想吃火锅，有什么别踩的雷？", "上一轮进图后应能提到花生，不要猜火锅"),
    )

    /** UI-kit function calling 演示：便于在助手对话里手工验收各类 renderer。 */
    val uiTalks: List<Probe> = listOf(
        Probe(
            "KV",
            "用键值卡显示：运行环境是端侧，渲染器是 Compose，状态是正常。只展示一次，不要再复述。",
            "应调用 render_kv，显示在助手 turn group 内",
        ),
        Probe(
            "表格",
            "用表格比较三个模块：ui-kit 已完成、artifacts 已完成、graph 已完成。不要输出 Markdown 表格。",
            "应调用 render_table，表格破泡并可点行看详情",
        ),
        Probe(
            "趋势图",
            "用折线图展示本周调用量：周一 8、周二 14、周三 11、周四 18。",
            "应调用 render_chart，Vico 折线图可展开",
        ),
        Probe(
            "占比图",
            "用饼图显示内容类型占比：Markdown 35、结构化组件 40、HTML 25。",
            "应调用 render_chart(kind=PIE)",
        ),
        Probe(
            "关系图",
            "用小型关系图展示：Relay 运行于 Android，Relay 包含 ui-kit，ui-kit 使用 Compose；标出关系。",
            "应调用 render_graph；关系来自本次明确给出的闭集",
        ),
        Probe(
            "HTML 文件",
            "生成 dashboard.html：做一个端侧运行状态仪表盘，含三个指标卡、CSS 动画和可切换的详情区域，不用外部资源。",
            "应调用 write_html_artifact，生成文件卡并可进入沙箱预览",
        ),
        Probe(
            "Markdown 文件",
            "生成 release-notes.md，包含版本摘要、已完成清单、风险和下一步。",
            "应调用 write_markdown_artifact，生成可预览的版本化文件卡",
        ),
    )

    /** 2026-08-23 导出的 12 轮求职对话，用于一键回放 Episode + Claim。 */
    val episodeClaimReplay: List<String> = listOf(
        "hello",
        "跳槽那个项目进展还是不尽人意，我已经搞1个月了",
        "主要是我留的时间太少了，我想下周就投简历",
        "项目经历包装问题",
        "我其实现在是硕士毕业2年，做了2年的安卓开发，最近1个月做了鸿蒙系统开发，安卓开发做了一个网络SDK,java+retrofit，做了一个车管家应用",
        "你觉得我改凸显什么",
        "车管家应用是个网络连接相关显示的应用，没啥子亮点，主要有一个卡片引擎，云端下发卡片，车端渲染成主界面。然后详情界面做成h5，车端提供jsb",
        """
        然后鸿蒙我是重新做了大范围的重构，各个业务放到feature里面流量查询显示，维保查询显示。。！同时还有common给app提供基础建设，基于策论模式提供的repository-用来给ui搞本地数据和网络数据的，有的先展示本地，云端回来再刷新，有的先显示加载中，云端失败，显示本地，同样h5基础架构也做了webview module, net-api统一提供网络接口封装，方便mock，直接写ui
        然后就是base
        net-core api的网络请求实际上委托给core了，封装了401重试获取token的逻辑让api不用关注这些复杂过程
        vehicle对车辆api进行封装，比如动力类型
        storage对系统存储peference ，数据库进行封装
        """.trimIndent(),
        "车管家是不是拍最前面，这是我花时间最多的，当然鸿蒙是我的集大成者",
        "客户端开发",
        "sdk也要吧",
        "一起写",
    )
}
