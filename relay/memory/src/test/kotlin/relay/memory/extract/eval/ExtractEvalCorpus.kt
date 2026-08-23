package relay.memory.extract.eval

/** High-intensity extract gold. Traps first; bulk covers the closed predicate set. */
internal object ExtractEvalCorpus {

    val samples: List<ExtractSample> by lazy { traps + buried + retracts + bulk }

    private val traps: List<ExtractSample> = listOf(
        sample(
            "trap-mom-likes-not-user",
            "trap", "family",
            dialogue = chat("我妈爱吃花生。"),
            gold = listOf(g("用户", "child_of", "妈妈"), g("妈妈", "likes", "花生")),
            forbidden = listOf(g("用户", "likes", "花生"), g("用户", "allergic_to", "花生")),
        ),
        sample(
            "trap-allergy-not-hotpot",
            "trap", "allergy",
            dialogue = chat("我花生过敏，今晚想吃火锅，底料得分开。"),
            gold = listOf(g("用户", "allergic_to", "花生")),
            forbidden = listOf(
                g("用户", "allergic_to", "火锅"),
                g("用户", "likes", "火锅"),
                g("用户", "likes", "花生"),
                g("用户", "dislikes", "花生"),
            ),
        ),
        sample(
            "trap-peanut-butter-alias",
            "trap", "allergy",
            dialogue = chat("记一下，我花生过敏，火锅蘸料也别推荐花生酱。"),
            gold = listOf(g("用户", "allergic_to", "花生")),
            forbidden = listOf(g("用户", "likes", "花生酱"), g("用户", "likes", "花生")),
        ),
        sample(
            "trap-homework-not-plans",
            "trap", "task",
            dialogue = chat("我作业没做完。"),
            gold = listOf(g("用户", "has_task", "作业")),
            forbidden = listOf(g("用户", "plans", "作业"), g("用户", "likes", "作业")),
        ),
        sample(
            "trap-work-years-not-company",
            "trap", "work",
            dialogue = chat("我工作两年了。"),
            gold = listOf(g("用户", "work_years", "两年")),
            forbidden = listOf(g("用户", "works_at", "两年"), g("用户", "works_as", "两年")),
        ),
        sample(
            "trap-english-language-not-skill",
            "trap", "lang",
            dialogue = chat("英语还能对付。"),
            gold = listOf(g("用户", "knows_language", "英语")),
            forbidden = listOf(g("用户", "skilled_in", "英语"), g("用户", "likes", "英语")),
        ),
        sample(
            "trap-jni-skill-not-like",
            "trap", "skill",
            dialogue = chat("JNI 我比较熟，明天评审那页留给我。"),
            gold = listOf(g("用户", "skilled_in", "JNI")),
            forbidden = listOf(g("用户", "likes", "JNI"), g("用户", "knows_language", "JNI")),
        ),
        sample(
            "trap-vegetarian-diet",
            "trap", "diet",
            dialogue = chat("最近吃素。"),
            gold = listOf(g("用户", "diet", "素食")),
            forbidden = listOf(g("用户", "likes", "素食"), g("用户", "likes", "吃素")),
        ),
        sample(
            "trap-subway-short-object",
            "trap", "prefer",
            dialogue = chat("通勤我宁可坐地铁，不爱开车。"),
            gold = listOf(g("用户", "prefers", "地铁"), g("用户", "dislikes", "开车")),
        ),
        sample(
            "trap-americano-short-object",
            "trap", "taste",
            dialogue = chat("咖啡我喝美式。千万别放香菜。"),
            gold = listOf(g("用户", "likes", "美式"), g("用户", "dislikes", "香菜")),
        ),
        sample(
            "trap-shanghai-jing-an-place-chain",
            "trap", "place",
            dialogue = chat("下周去上海两天，住静安。机票还没买。"),
            gold = listOf(g("用户", "plans", "上海"), g("上海", "located_in", "静安")),
            forbidden = listOf(
                g("用户", "located_in", "静安"),
                g("用户", "lives_in", "上海"),
                g("用户", "plans", "机票"),
                g("用户", "has_task", "机票"),
            ),
        ),
        sample(
            "trap-no-ticket-no-reminder",
            "trap", "noise",
            dialogue = chat("提醒我周末买猫粮。机票还在犹豫。过年再说。"),
            gold = emptyList(),
            forbidden = listOf(
                g("用户", "plans", "机票"),
                g("用户", "has_task", "提醒"),
                g("用户", "plans", "过年"),
                g("用户", "attends", "周末"),
            ),
        ),
        sample(
            "trap-chitchat-empty",
            "trap", "noise",
            dialogue = chat("今天风好大，西湖边估计没人。随便聊聊就行，没什么要记的。", "那就不记。"),
            gold = emptyList(),
        ),
        sample(
            "trap-product-change-not-like",
            "trap", "noise",
            dialogue = chat("哎，今天那个需求又变了，下周三就要交了，这产品一直变，我是真服了。"),
            gold = emptyList(),
            forbidden = listOf(
                g("用户", "likes", "产品"),
                g("用户", "dislikes", "产品"),
                g("用户", "has_task", "需求"),
                g("用户", "plans", "下周三"),
            ),
        ),
        sample(
            "trap-colleague-direction",
            "trap", "people",
            dialogue = chat("明天评审找王磊，他是我同事，比我熟 JNI。"),
            gold = listOf(g("用户", "colleague_of", "王磊"), g("王磊", "skilled_in", "JNI")),
            forbidden = listOf(g("王磊", "colleague_of", "用户"), g("用户", "skilled_in", "JNI")),
        ),
        sample(
            "trap-cat-named-direction",
            "trap", "pet",
            dialogue = chat("家里那只猫叫芝麻。"),
            gold = listOf(g("用户", "has_pet", "猫"), g("猫", "named", "芝麻")),
            forbidden = listOf(g("芝麻", "named", "猫"), g("用户", "named", "芝麻")),
        ),
        sample(
            "trap-live-vs-office-vs-born",
            "trap", "place",
            dialogue = chat("我在杭州住，办公还在西溪。我是合肥出生的。"),
            gold = listOf(
                g("用户", "lives_in", "杭州"),
                g("用户", "work_location", "西溪"),
                g("用户", "born_in", "合肥"),
            ),
            forbidden = listOf(
                g("用户", "lives_in", "西溪"),
                g("用户", "work_location", "杭州"),
                g("用户", "located_in", "杭州"),
            ),
        ),
        sample(
            "trap-quit-means-job-change-and-rest",
            "trap", "plans",
            dialogue = chat("我打算离职，先歇两个月，别的先不谈。"),
            gold = listOf(g("用户", "plans", "跳槽"), g("用户", "plans", "休息")),
            forbidden = listOf(g("用户", "plans", "两个月")),
        ),
        sample(
            "trap-penicillin-allergy-history",
            "trap", "allergy",
            dialogue = chat("医院问过敏史的话，青霉素要报。我小时候打过，起过疹子。"),
            gold = listOf(g("用户", "allergic_to", "青霉素")),
            forbidden = listOf(g("用户", "likes", "青霉素"), g("用户", "dislikes", "青霉素")),
        ),
        sample(
            "trap-moved-city-latest-wins",
            "trap", "functional",
            dialogue = chat("我住杭州。不对，上周搬上海了。"),
            gold = listOf(g("用户", "lives_in", "上海")),
            forbidden = listOf(g("用户", "lives_in", "杭州")),
        ),
        sample(
            "trap-halal-not-vegetarian",
            "trap", "diet",
            dialogue = chat("我吃清真，别按素食推。"),
            gold = listOf(g("用户", "diet", "清真")),
            forbidden = listOf(g("用户", "diet", "素食")),
        ),
        sample(
            "trap-spouse-not-family-of",
            "trap", "family",
            dialogue = chat("我对象叫陈晨。"),
            gold = listOf(g("用户", "spouse_of", "陈晨")),
            forbidden = listOf(g("用户", "family_of", "陈晨"), g("用户", "friend_of", "陈晨")),
        ),
        sample(
            "trap-parent-of-child",
            "trap", "family",
            dialogue = chat("我女儿小宝今年上幼儿园。"),
            gold = listOf(g("用户", "parent_of", "小宝")),
            forbidden = listOf(g("用户", "child_of", "小宝"), g("用户", "sibling_of", "小宝")),
        ),
        sample(
            "trap-sister",
            "trap", "family",
            dialogue = chat("我姐姐住北京，她爱吃香菜。"),
            gold = listOf(
                g("用户", "sibling_of", "姐姐"),
                g("姐姐", "lives_in", "北京"),
                g("姐姐", "likes", "香菜"),
            ),
            forbidden = listOf(g("用户", "likes", "香菜")),
        ),
        sample(
            "trap-no-assistant-entity",
            "trap", "noise",
            dialogue = "用户: 你记性挺好。\n助理: 过奖了。",
            gold = emptyList(),
            forbidden = listOf(g("助理", "likes", "记性"), g("用户", "friend_of", "助理")),
        ),
        sample(
            "trap-device-not-fact",
            "trap", "noise",
            dialogue = chat("Mate 70 上要盯热和电，别把我岗位记成后端。我在阿里做客户端。"),
            gold = listOf(g("用户", "works_at", "阿里"), g("用户", "works_as", "客户端")),
            forbidden = listOf(
                g("用户", "owns", "Mate 70"),
                g("用户", "works_as", "后端"),
                g("用户", "likes", "Mate 70"),
            ),
        ),
        sample(
            "trap-calcium-takes",
            "trap", "meds",
            dialogue = chat("医生让我吃钙片。"),
            gold = listOf(g("用户", "takes", "钙片")),
            forbidden = listOf(g("用户", "likes", "钙片"), g("用户", "has_task", "钙片")),
        ),
        sample(
            "trap-night-school-attends",
            "trap", "attends",
            dialogue = chat("我在上夜校。"),
            gold = listOf(g("用户", "attends", "夜校")),
            forbidden = listOf(g("用户", "alumni_of", "夜校"), g("用户", "works_at", "夜校")),
        ),
        sample(
            "trap-bike-owns",
            "trap", "owns",
            dialogue = chat("我有一辆自行车。"),
            gold = listOf(g("用户", "owns", "自行车")),
        ),
        sample(
            "trap-zheda-alumni",
            "trap", "school",
            dialogue = chat("浙大毕业的。"),
            gold = listOf(g("用户", "alumni_of", "浙大")),
            forbidden = listOf(g("用户", "works_at", "浙大"), g("用户", "member_of", "浙大")),
        ),
        sample(
            "trap-basketball-member",
            "trap", "org",
            dialogue = chat("我在篮球队。"),
            gold = listOf(g("用户", "member_of", "篮球队")),
        ),
        sample(
            "trap-mom-lives-ningbo",
            "trap", "family",
            dialogue = chat("我妈住宁波，过年我过去。别把她日程和我杭州的会叠在一起。"),
            gold = listOf(
                g("用户", "child_of", "妈妈"),
                g("妈妈", "lives_in", "宁波"),
                g("用户", "lives_in", "杭州"),
            ),
            forbidden = listOf(g("用户", "lives_in", "宁波"), g("用户", "family_of", "妈妈")),
        ),
        sample(
            "trap-keep-shanghai-plan-if-not-cancelled",
            "trap", "plans",
            dialogue = chat("下周上海静安那两天可能取消，但先别从计划里划掉，机票还在犹豫。"),
            gold = listOf(g("用户", "plans", "上海"), g("上海", "located_in", "静安")),
            forbidden = listOf(g("用户", "plans", "机票")),
        ),
        sample(
            "trap-homework-aka-gongke",
            "trap", "task",
            dialogue = chat("我功课没做完。"),
            gold = listOf(g("用户", "has_task", "作业")),
        ),
        sample(
            "trap-friend-lina",
            "trap", "people",
            dialogue = chat("朋友李娜。"),
            gold = listOf(g("用户", "friend_of", "李娜")),
            forbidden = listOf(g("用户", "colleague_of", "李娜"), g("李娜", "friend_of", "用户")),
        ),
        sample(
            "trap-grandma-family-of",
            "trap", "family",
            dialogue = chat("外婆爱喝豆浆，她住杭州。"),
            gold = listOf(
                g("用户", "family_of", "外婆"),
                g("外婆", "likes", "豆浆"),
                g("外婆", "lives_in", "杭州"),
            ),
            forbidden = listOf(g("用户", "child_of", "外婆"), g("用户", "likes", "豆浆")),
        ),
    )

    private val buried: List<ExtractSample> = listOf(
        sample(
            "buried-quit-in-vent",
            "trap", "buried", "live-style",
            dialogue = """
                |用户: 今天好烦啊，目标没完成
                |助理: 烦得正常。卡在哪了。
                |用户: 需求又变了，下周三就要交，这产品一直变
                |助理: 确实烦。
                |用户: 我这几天经常为了跳槽的事情睡不好，本来想跟现在的环境说拜拜，然后休息一段时间，现在看好难啊
                |助理: 想走、想歇，今晚先别改简历。
                |用户: 主要是现在工作很累，长期压榨了成长，薪资成长空间也一般
                |助理: 跳槽和休息都说得通。
                |用户: 好的，over
                |助理: 好。
            """.trimMargin(),
            gold = listOf(g("用户", "plans", "跳槽"), g("用户", "plans", "休息")),
            forbidden = listOf(
                g("用户", "likes", "产品"),
                g("用户", "has_task", "需求"),
                g("用户", "plans", "简历"),
            ),
        ),
        sample(
            "buried-long-day",
            "trap", "buried", "dense",
            dialogue = """
                |用户: 早上我在杭州家里给芝麻换了猫砂。中午和王磊吃了面，他点了花生酱，我没动，你知道我花生过敏。下午还是阿里这边的客户端活。晚上医生说维生素D别停。我妈从宁波打电话来。通勤还是地铁。下周去上海住静安。
                |助理: 收到。
            """.trimMargin(),
            gold = listOf(
                g("用户", "lives_in", "杭州"),
                g("用户", "has_pet", "猫"),
                g("猫", "named", "芝麻"),
                g("用户", "colleague_of", "王磊"),
                g("用户", "allergic_to", "花生"),
                g("用户", "works_at", "阿里"),
                g("用户", "works_as", "客户端"),
                g("用户", "takes", "维生素D"),
                g("用户", "child_of", "妈妈"),
                g("妈妈", "lives_in", "宁波"),
                g("用户", "prefers", "地铁"),
                g("用户", "plans", "上海"),
                g("上海", "located_in", "静安"),
            ),
            forbidden = listOf(
                g("用户", "likes", "花生酱"),
                g("用户", "allergic_to", "火锅"),
            ),
        ),
        sample(
            "buried-play-pack",
            "trap", "buried", "dense",
            dialogue = """
                |用户: 我妈爱吃花生，今晚她来杭州吃饭。我自己花生过敏，火锅底料得分开。家里猫叫芝麻。
                |助理: 妈妈花生、你过敏、芝麻。
                |用户: 我作业没做完。白天在阿里做客户端，工作两年了，办公还在西溪。浙大毕业的。同事王磊，明天评审 JNI 留给他。
                |助理: 作业、阿里、两年、西溪、王磊。
                |用户: 咖啡我喝美式，不爱香菜。通勤宁可坐地铁。最近吃素。医生让我吃钙片。下周去上海住静安。英语还能对付。朋友李娜。我打算离职。
                |助理: 记下了。
            """.trimMargin(),
            gold = listOf(
                g("用户", "child_of", "妈妈"),
                g("妈妈", "likes", "花生"),
                g("用户", "allergic_to", "花生"),
                g("用户", "lives_in", "杭州"),
                g("用户", "has_pet", "猫"),
                g("猫", "named", "芝麻"),
                g("用户", "has_task", "作业"),
                g("用户", "works_at", "阿里"),
                g("用户", "works_as", "客户端"),
                g("用户", "work_years", "两年"),
                g("用户", "work_location", "西溪"),
                g("用户", "alumni_of", "浙大"),
                g("用户", "colleague_of", "王磊"),
                g("用户", "likes", "美式"),
                g("用户", "dislikes", "香菜"),
                g("用户", "prefers", "地铁"),
                g("用户", "diet", "素食"),
                g("用户", "takes", "钙片"),
                g("用户", "plans", "上海"),
                g("上海", "located_in", "静安"),
                g("用户", "knows_language", "英语"),
                g("用户", "friend_of", "李娜"),
                g("用户", "plans", "跳槽"),
            ),
            forbidden = listOf(g("用户", "likes", "花生")),
        ),
    )

    private val retracts: List<ExtractSample> = listOf(
        sample(
            "retract-us-visa",
            "trap", "retract",
            dialogue = chat("不去美国了，签证没过。"),
            prior = listOf(g("用户", "plans", "美国")),
            gold = listOf(g("用户", "plans", "美国", retract = true)),
            forbidden = listOf(g("用户", "plans", "美国")),
        ),
        sample(
            "retract-only-stated-plan",
            "trap", "retract",
            dialogue = chat("我不打算去了，签证没过。"),
            prior = listOf(g("用户", "plans", "美国")),
            gold = listOf(g("用户", "plans", "美国", retract = true)),
        ),
        sample(
            "retract-do-not-invent-target",
            "trap", "retract",
            dialogue = chat("那趟取消了。"),
            prior = listOf(g("用户", "plans", "美国"), g("用户", "plans", "上海")),
            gold = emptyList(),
            forbidden = listOf(
                g("用户", "plans", "美国", retract = true),
                g("用户", "plans", "上海", retract = true),
            ),
        ),
        sample(
            "project-car-card-engine",
            "trap", "project",
            dialogue = chat(
                "我做过车管家，主界面有卡片引擎，云端下发卡片、车端渲染，详情页是 H5，车端提供 JSBridge。",
            ),
            gold = listOf(
                g("用户", "worked_on", "车管家"),
                g("车管家", "has_component", "卡片引擎"),
                g("车管家", "uses_technology", "H5"),
                g("车管家", "uses_technology", "JSBridge"),
            ),
            goldClaims = listOf(GoldClaim("车管家通过云端下发卡片并由车端动态渲染主界面", "车管家")),
        ),
        sample(
            "project-harmony-repository",
            "trap", "project", "buried",
            dialogue = chat(
                "鸿蒙项目做了大范围重构，Repository 用策略模式，有的先展示本地再刷新，有的云端失败后回退本地。",
            ),
            gold = emptyList(),
            goldClaims = listOf(
                GoldClaim("用户在鸿蒙项目中用策略模式实现 Repository 的本地与网络数据加载策略"),
            ),
            forbidden = listOf(g("用户", "skilled_in", "策略模式")),
        ),
        sample(
            "project-target-role-context",
            "trap", "project",
            dialogue = "用户: 车管家和鸿蒙哪个放前面？\n助理: 你想投什么方向？\n用户: 客户端开发\n助理: 那车管家放前面。",
            gold = listOf(g("用户", "target_role", "客户端开发")),
        ),
    )

    private val bulk: List<ExtractSample> = buildList {
        fun addBulk(id: String, user: String, vararg gold: GoldTriple) {
            add(sample(id, "bulk", dialogue = chat(user), gold = gold.toList()))
        }
        listOf("花生", "青霉素", "头孢", "芒果", "虾", "尘螨", "花粉", "牛奶", "鸡蛋", "小麦", "海鲜", "坚果")
            .forEach { addBulk("bulk-allergy-$it", "我${it}过敏。", g("用户", "allergic_to", it)) }
        listOf("杭州", "上海", "北京", "宁波", "合肥", "深圳", "成都", "广州", "南京", "苏州")
            .forEach { addBulk("bulk-lives-$it", "我在${it}住。", g("用户", "lives_in", it)) }
        listOf("西溪", "漕河泾", "望京", "珠江新城", "高新")
            .forEach { addBulk("bulk-office-$it", "我办公在${it}。", g("用户", "work_location", it)) }
        listOf("合肥", "安庆", "绍兴")
            .forEach { addBulk("bulk-born-$it", "我${it}出生的。", g("用户", "born_in", it)) }
        listOf("阿里", "字节", "腾讯", "美团", "网易", "华为", "小米", "蚂蚁")
            .forEach { addBulk("bulk-company-$it", "我在${it}上班。", g("用户", "works_at", it)) }
        listOf("客户端", "架构", "后端", "设计", "产品")
            .forEach { addBulk("bulk-role-$it", "我做${it}。", g("用户", "works_as", it)) }
        listOf("美式", "火锅", "手冲", "米线", "抹茶", "围棋", "游泳", "跑步")
            .forEach { addBulk("bulk-likes-$it", "我喜欢${it}。", g("用户", "likes", it)) }
        listOf("香菜", "内脏", "香椿", "苦瓜", "葱")
            .forEach { addBulk("bulk-dislikes-$it", "我不爱${it}。", g("用户", "dislikes", it)) }
        listOf("地铁" to "通勤我宁可坐地铁。", "步行" to "通勤我宁可步行。", "骑车" to "通勤我宁可骑车。")
            .forEach { (mode, line) -> addBulk("bulk-prefers-$mode", line, g("用户", "prefers", mode)) }
        listOf("王磊", "张伟", "阿秀", "刘洋", "陈晨")
            .forEach { addBulk("bulk-colleague-$it", "${it}是我同事。", g("用户", "colleague_of", it)) }
        listOf("李娜", "小周", "阿宁")
            .forEach { addBulk("bulk-friend-$it", "朋友${it}。", g("用户", "friend_of", it)) }
        listOf("英语", "日语", "法语", "德语")
            .forEach { addBulk("bulk-lang-$it", "我会${it}。", g("用户", "knows_language", it)) }
        listOf("Kotlin", "JNI", "SQL", "摄影")
            .forEach { addBulk("bulk-skill-$it", "我擅长${it}。", g("用户", "skilled_in", it)) }
        listOf("浙大", "复旦", "南大", "中科大")
            .forEach { addBulk("bulk-school-$it", "${it}毕业的。", g("用户", "alumni_of", it)) }
        listOf("篮球队", "读书会", "开源社")
            .forEach { addBulk("bulk-org-$it", "我在${it}。", g("用户", "member_of", it)) }
        listOf("素食", "清真", "无麸质")
            .forEach { addBulk("bulk-diet-$it", "我吃${it}。", g("用户", "diet", it)) }
        listOf("钙片", "维生素D", "维生素", "降压药")
            .forEach { addBulk("bulk-takes-$it", "医生让我吃${it}。", g("用户", "takes", it)) }
        listOf("自行车", "相机", "钢琴")
            .forEach { addBulk("bulk-owns-$it", "我有${it}。", g("用户", "owns", it)) }
        listOf("夜校", "瑜伽", "游泳课")
            .forEach { addBulk("bulk-attends-$it", "我在上${it}。", g("用户", "attends", it)) }
        listOf("美国" to "我打算去美国。", "日本" to "我打算去日本。", "考研" to "我打算考研。", "买房" to "我打算买房。")
            .forEach { (place, line) -> addBulk("bulk-plans-$place", line, g("用户", "plans", place)) }
        listOf("作业", "报销", "搬家")
            .forEach { addBulk("bulk-task-$it", "我还要交${it}。", g("用户", "has_task", it)) }
        listOf("两年" to "我工作两年了。", "三年" to "我工龄三年。")
            .forEach { (years, line) -> addBulk("bulk-years-$years", line, g("用户", "work_years", years)) }
        listOf("芝麻", "豆豆", "橘子")
            .forEach { addBulk("bulk-cat-$it", "家里猫叫${it}。", g("用户", "has_pet", "猫"), g("猫", "named", it)) }
        listOf("静安" to "上海", "西湖" to "杭州", "鼓楼" to "南京")
            .forEach { (inner, outer) ->
                addBulk(
                    "bulk-located-$inner",
                    "${inner}在${outer}。",
                    g(inner, "located_in", outer),
                )
            }
        listOf("车管家", "支付客户端")
            .forEach { addBulk("bulk-worked-on-$it", "我做过${it}。", g("用户", "worked_on", it)) }
        addBulk("bulk-component-card", "车管家包含卡片引擎。", g("车管家", "has_component", "卡片引擎"))
        addBulk("bulk-technology-jsb", "车管家使用 JSBridge。", g("车管家", "uses_technology", "JSBridge"))
        addBulk("bulk-target-role", "我这次想投客户端开发。", g("用户", "target_role", "客户端开发"))
    }

    private fun sample(
        id: String,
        vararg tags: String,
        dialogue: String,
        gold: List<GoldTriple>,
        prior: List<GoldTriple> = emptyList(),
        forbidden: List<GoldTriple> = emptyList(),
        aliases: Map<String, String> = emptyMap(),
        goldClaims: List<GoldClaim> = emptyList(),
        forbiddenClaims: List<GoldClaim> = emptyList(),
    ): ExtractSample = ExtractSample(
        id = id,
        tags = tags.toSet(),
        dialogue = dialogue,
        gold = gold,
        prior = prior,
        forbidden = forbidden,
        aliases = aliases,
        goldClaims = goldClaims,
        forbiddenClaims = forbiddenClaims,
    )
}
