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
            hint = "集合边并存、别人的口味。美式和拿铁同时在",
            drafts = listOf(
                t("用户", "likes", "拿铁"),
                t("用户", "likes", "火锅"),
                t("用户", "likes", "手冲"),
                t("用户", "likes", "米线"),
                t("用户", "dislikes", "内脏"),
                t("用户", "plans", "跳槽"),
                t("用户", "plans", "考研"),
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
        Probe("林晚", "林晚", "应空，小说节点不在助手图"),
    )
}
