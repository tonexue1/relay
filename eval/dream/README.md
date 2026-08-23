# Dream eval · S1 / S1c / S2

> 不是记忆引擎。S1 端侧 3B 真对话已否；下一闸是 **S1c 云端抽取**。见 [docs/spikes.md](../../docs/spikes.md)。
> 当前云端抽取 + 夜里并节点的评测集在 [eval/memory](../memory/README.md)，不要用本目录顶替。

## 它验什么

| 命令 | spike | 过闸 |
|---|---|---|
| `python extract.py` → `python score.py` | S1 端侧 3B | **真对话 FAIL**；合成可过，不再救 |
| `python extract_cloud.py` → `python score.py --pred out/extract-cloud.jsonl` | **S1c** | live P≥70% R≥50%，抽出 `plans 跳槽/休息` |
| `python inject.py` | S2 注入 | 种子已过(83% vs 17%) |

云端默认整段会话一次请求(`CLOUD_MAX_CHUNK_CHARS=4000`)。Key：`RELAY_DEEPSEEK_API_KEY` 或仓库根 `local.properties` 的 `relay.deepseek.apiKey`。

```bash
python extract_cloud.py --dry-run
python extract_cloud.py
python score.py --pred out/extract-cloud.jsonl
```

流程停在 **切段 → 抽取 → 代码滤脏边 → 打分**。没有 upsert / Pipeline。

## 一次跑完

需要 Python 3.12+（仓库机器上是 `python3.12`）。约 1.9GB GGUF，第一次会下载。

```bash
cd eval/dream
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python download.py                 # bartowski Qwen2.5-3B-Instruct-Q4_K_M.gguf
python score.py --self-test        # 不加载模型
python extract.py --dry-run        # 只看代码怎么切段
python extract.py                  # 跑 S1
python score.py
python inject.py                   # 跑 S2
```

已有 GGUF 时：

```bash
export RELAY_GGUF=/path/to/Qwen2.5-3B-Instruct-Q4_K_M.gguf
```

Apple Silicon 默认 `n_gpu_layers=-1`（Metal）。纯 CPU：`export RELAY_N_GPU_LAYERS=0`。

## 代码怎么切（对应做梦的前两刀）

`common.chunk_turns`：先按**一轮对话**，超 `MAX_CHUNK_CHARS`（默认 140）再按句/逗号切。模型不参与选切点。few-shot 把 `n_ctx` 提到 4096。抽完后 `clean_triples` 丢掉未在原文出现的边、机票/提醒、非用户主语，并纠正 `colleague_of` / 宠物 `named` 方向。

## 样本

- `samples/extract.json` — 种子 + `ex-15-live` 真人对话。
- `samples/inject.json` — 12 道必须靠记忆的题 + 2 道对照（注入不该打坏常识）。

真实对话请匿名后另存，不要提交隐私原文。

三元组 schema（GBNF 锁死谓语）：

```json
{"triples":[{"s":"用户","p":"allergic_to","o":"花生"}]}
```

谓语闭集 30 条,中文标签在 `common.PREDICATE_ZH`。改词表时同步改 `grammar/triple.gbnf` 的 `pred-name` 与 `common.PREDICATES`。

## 产出

写在 `out/`（gitignored）：

- `out/extract.jsonl` — 端侧 3B
- `out/extract-cloud.jsonl` — S1c DeepSeek
- `out/inject.jsonl` — S2

## 不要用它做的

- 在 Python 里实现做梦引擎或 Room 的镜像
- 用 Mac 吞吐代替 Mate 70 的热/电
- 为了「先能跑」用云端大模型顶替 3B 去**假装端侧 S1 过了**——云端抽取走 `extract_cloud.py`(S1c)，别和端侧混报
