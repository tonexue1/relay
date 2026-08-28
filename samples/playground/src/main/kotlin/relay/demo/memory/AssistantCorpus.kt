package relay.demo.memory

/**
 * Playground chips and a 12-turn replay script. Memory writes go through the ledger Runtime.
 */
object AssistantCorpus {

    data class Probe(val label: String, val prompt: String, val expect: String)

    val talks: List<Probe> = listOf(
        Probe("过敏", "我花生过敏，火锅别放花生。", "应进原文，后续召回能垫到花生"),
        Probe("我妈", "我妈爱吃花生。", "应进原文"),
        Probe("离职", "我打算离职，先歇一阵。", "应进原文"),
        Probe("签证", "不去美国了，签证没过。", "应进原文"),
        Probe("火锅", "今晚想吃火锅，有什么别踩的雷？", "上一轮进库后应能提到花生"),
    )

    val uiTalks: List<Probe> = listOf(
        Probe(
            "KV",
            "用键值卡显示：运行环境是端侧，渲染器是 Compose，状态是正常。只展示一次，不要再复述。",
            "应调用 render_kv，显示在助手 turn group 内",
        ),
        Probe(
            "表格",
            "用表格比较三个模块：ui-kit 已完成、artifacts 已完成、memory 已完成。不要输出 Markdown 表格。",
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
