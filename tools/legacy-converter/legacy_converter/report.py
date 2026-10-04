"""Local, escaped human-readable reports; no network or executable imported content."""
import html
import json
from .labels import reason_label
from .diagnostics import CATEGORY_LABELS


def write_html(path, report):
    summary = report["summary"]
    escape = lambda value: html.escape(str(value), quote=True)
    labels = {"plugins": "插件", "mappedVariables": "已迁移变量", "convertedImages": "图片",
              "convertedActiveOperations": "活跃操作", "preservedDisabled": "保留禁用", "unsupportedItems": "待迁移项"}
    cards = "".join('<div class="card"><small>' + escape(label) + '</small><strong>' +
                    escape(summary.get(key, summary.get("convertedOperations", 0) if key == "convertedActiveOperations" else 0)) +
                    '</strong></div>' for key, label in labels.items())
    tasks = []
    for item in report.get("remainingTasks", []):
        tasks.append('<tr><td>' + escape(item["phase"]) + '</td><td>' + escape(item["title"]) +
                     '</td><td>' + escape(item["affectedItems"]) + '</td><td>' + escape(item["owner"]) +
                     '</td><td>' + escape(item["action"]) + '</td></tr>')
    rows = []
    for issue in report["blockers"][:1000]:
        record = json.dumps(issue.get("record"), ensure_ascii=False, indent=2)
        rows.append('<tr><td>' + escape(issue["source"]) + '</td><td>' + escape(issue["line"]) +
                    '</td><td>' + escape(CATEGORY_LABELS.get(issue.get("category"), "其他待核对")) +
                    '</td><td>' + escape(reason_label(issue["reason"])) + '<details><summary>查看原字段</summary><pre>' +
                    escape(record) + '</pre></details></td></tr>')
    status = {"blocked": "尚有未迁移语义，不能运行", "converted": "转换完成，编译验证通过",
              "candidate-needs-compiler-validation": "候选工程，尚未经过编译验证"}[summary["status"]]
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>智构 · 转换报告</title>
<meta name="viewport" content="width=device-width,initial-scale=1"><style>
body{margin:0;background:#f3f6fc;color:#243248;font:14px "Microsoft YaHei",sans-serif}
main{max-width:1120px;margin:32px auto;padding:24px;background:white;border-radius:16px}
h1{color:#386bff;font-size:24px}p{line-height:1.8;color:#66758b}.cards{display:flex;gap:12px;flex-wrap:wrap}
.card{flex:1;padding:18px;background:#f5f7fc;border-radius:10px;min-width:100px}
small{display:block;color:#68758b}strong{display:block;font-size:28px;color:#386bff;margin-top:8px}
input{box-sizing:border-box;width:100%;padding:12px;border:1px solid #dce3ef;border-radius:8px;margin:20px 0}
table{width:100%;border-collapse:collapse}th,td{text-align:left;border-bottom:1px solid #e6ebf3;padding:12px;vertical-align:top}
th{background:#f5f7fc}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f7f8fc;padding:12px}
summary{cursor:pointer;color:#386bff;margin-top:8px}a{color:#386bff}.status{padding:12px;background:#edf3ff;border-radius:8px}
</style><main><h1>旧工程转换报告</h1><p class="status">STATUS</p><section class="cards">CARDS</section>
<p>原工程保存在 original.zip；完整映射、原字段和所有待迁移项见 <a href="report.json">report.json</a>。
逐行表最多显示前1000项，筛选仅对这1000项生效；工具分类筛选覆盖完整记录。报告含私人脚本，请勿上传或提交到仓库。</p>
<h2>补全计划</h2><p>按阶段排序；影响项包含相关分支标记，不代表不同功能的数量。
界面字段、控件及事件审计见 <a href="ui-audit.json">ui-audit.json</a>；函数逐调用参数形状与待核对项见 <a href="function-audit.json">function-audit.json</a>。静态审计不等于功能已转换，不执行旧函数。</p>
<table><thead><tr><th>阶段</th><th>功能</th><th>影响项</th><th>所缺内容</th><th>实施要求</th></tr></thead><tbody id="tasks">TASKROWS</tbody></table>
<h2>逐行缺口</h2><input id="search" placeholder="按源文件、分类或原因筛选"><table><thead><tr><th>源文件</th><th>行</th><th>分类</th><th>原因 / 原记录</th></tr></thead><tbody id="issues">ROWS</tbody></table>
</main><script>document.getElementById('search').addEventListener('input',function(){let q=this.value.toLowerCase();
document.querySelectorAll('#issues tr').forEach(function(r){r.hidden=!r.textContent.toLowerCase().includes(q)});});</script></html>'''
    # Substitute once: private record text must never become template syntax or Javascript.
    import re
    fragments = {"STATUS": escape(status), "CARDS": cards, "TASKROWS": "".join(tasks), "ROWS": "".join(rows)}
    page = re.sub(r"STATUS|CARDS|TASKROWS|ROWS", lambda match: fragments[match.group()], page)
    path.write_text(page, encoding="utf-8")
