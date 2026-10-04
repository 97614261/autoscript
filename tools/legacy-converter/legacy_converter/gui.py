"""Local Tk desktop UI. Workers never touch widgets; imported code is never executed."""
import collections
import datetime
import hashlib
import json
import os
import pathlib
import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from . import __version__
from .core import Cancelled, Converter, Job, LegacyArchive
from .validation import bundled_validator, compiler_gate
from .labels import failure_message, reason_label
from .diagnostics import CATEGORY_LABELS, inspect_issue

STAGES = {"prepare": "准备输出", "plugins": "转换插件", "resources": "转换资源",
          "validate": "编译检查", "publish": "完成输出"}


class ConverterWindow:
    def __init__(self, root):
        self.root = root
        root.title("智构 · 独立工程转换工具")
        root.geometry("980x720")
        root.minsize(760, 600)
        root.configure(background="#f3f6fc")
        self.events = queue.Queue(maxsize=64)
        self.cancel = threading.Event()
        self.worker = None
        self.closing = False
        self.log_lines = collections.deque(maxlen=800)
        self.stage = None
        self.percent = -1
        self.fingerprint = None
        self.analyzed_path = None
        self.last_output = None
        self.pending_output = None
        self.issue_records = []
        self.plugin_aliases = {}
        self.remaining_tasks = []
        self.archive_path = tk.StringVar()
        self.output_parent = tk.StringVar(value=str(pathlib.Path(__file__).resolve().parents[1] / "output"))
        self.entry = tk.StringVar()
        self.status = tk.StringVar(value="先选择旧工程ZIP，工具会分析可迁移内容。")
        self.stats = tk.StringVar(value="插件 —    变量 —    图片 —    基础操作 —    待迁移 —")
        style = ttk.Style(root)
        style.theme_use("clam")
        style.configure("TFrame", background="#ffffff")
        style.configure("TLabel", background="#ffffff", foreground="#26354c", font=("Microsoft YaHei", 10))
        style.configure("TButton", padding=(12, 6), font=("Microsoft YaHei", 10), anchor="center")
        style.configure("Accent.TButton", background="#386bff", foreground="white")
        style.map("Accent.TButton", background=[("disabled", "#b8c8f0"), ("active", "#2758df")])
        style.configure("Treeview", rowheight=26, font=("Microsoft YaHei", 9))
        style.configure("Treeview.Heading", font=("Microsoft YaHei", 9, "bold"))
        main = ttk.Frame(root, padding=18)
        main.pack(fill="both", expand=True, padx=14, pady=14)
        ttk.Label(main, text="旧工程 → 智构", font=("Microsoft YaHei", 20, "bold"), foreground="#386bff").pack(anchor="w")
        ttk.Label(main, text="独立离线工具  ·  原文件不修改  ·  未支持项明确报告  ·  v" + __version__, foreground="#7c8ba0").pack(anchor="w", pady=(2, 14))
        form = ttk.Frame(main)
        form.pack(fill="x")
        form.columnconfigure(1, weight=1)
        ttk.Label(form, text="旧工程ZIP").grid(row=0, column=0, padx=(0, 12), pady=4, sticky="w")
        self.file_field = ttk.Entry(form, textvariable=self.archive_path)
        self.file_field.grid(row=0, column=1, sticky="ew", pady=4)
        self.choose_button = ttk.Button(form, text="选择文件", command=self.choose_archive)
        self.choose_button.grid(row=0, column=2, padx=(8, 0))
        ttk.Label(form, text="输出位置").grid(row=1, column=0, sticky="w", pady=4)
        self.output_field = ttk.Entry(form, textvariable=self.output_parent)
        self.output_field.grid(row=1, column=1, sticky="ew", pady=4)
        self.output_button = ttk.Button(form, text="选择目录", command=self.choose_output)
        self.output_button.grid(row=1, column=2, padx=(8, 0))
        ttk.Label(form, text="入口插件").grid(row=2, column=0, sticky="w", pady=4)
        self.entry_box = ttk.Combobox(form, textvariable=self.entry, state="readonly")
        self.entry_box.grid(row=2, column=1, sticky="ew", pady=4)
        self.analyze_button = ttk.Button(form, text="重新分析", command=self.analyze)
        self.analyze_button.grid(row=2, column=2, padx=(8, 0))
        ttk.Label(main, textvariable=self.stats, foreground="#386bff").pack(fill="x", pady=(14, 6))
        self.progress = ttk.Progressbar(main, mode="determinate", maximum=100)
        self.progress.pack(fill="x", pady=4)
        ttk.Label(main, textvariable=self.status, wraplength=850).pack(anchor="w", pady=(4, 10))
        notebook = ttk.Notebook(main)
        notebook.pack(fill="both", expand=True)
        logs = ttk.Frame(notebook, padding=6)
        issues = ttk.Frame(notebook, padding=6)
        notebook.add(logs, text="运行日志")
        notebook.add(issues, text="待迁移项")
        filter_row = ttk.Frame(issues)
        filter_row.pack(fill="x", pady=(0, 6))
        ttk.Label(filter_row, text="分类").pack(side="left", padx=(0, 8))
        self.issue_category = tk.StringVar(value="全部")
        self.category_box = ttk.Combobox(filter_row, textvariable=self.issue_category, state="readonly",
                                        values=["全部"] + list(CATEGORY_LABELS.values()), width=24)
        self.category_box.pack(side="left")
        self.category_box.bind("<<ComboboxSelected>>", lambda event: self.refresh_issues())
        ttk.Label(filter_row, text="双击查看原字段 / 转换节点", foreground="#7c8ba0").pack(side="right")
        self.log_view = tk.Text(logs, state="disabled", wrap="word", height=12, font=("Consolas", 10),
                                background="#f8faff", foreground="#33445e", relief="flat", padx=10, pady=10)
        log_scroll = ttk.Scrollbar(logs, command=self.log_view.yview)
        self.log_view.configure(yscrollcommand=log_scroll.set)
        log_scroll.pack(side="right", fill="y")
        self.log_view.pack(fill="both", expand=True)
        self.issues = ttk.Treeview(issues, columns=("source", "line", "reason"), show="headings", height=10)
        for key, label, width in (("source", "源文件", 210), ("line", "行", 55), ("reason", "未迁移原因", 500)):
            self.issues.heading(key, text=label)
            self.issues.column(key, width=width, anchor="center" if key == "line" else "w")
        issue_scroll = ttk.Scrollbar(issues, command=self.issues.yview)
        self.issues.configure(yscrollcommand=issue_scroll.set)
        issue_scroll.pack(side="right", fill="y")
        self.issues.pack(fill="both", expand=True)
        self.issues.bind("<Double-1>", self.show_issue)
        tasks_panel = ttk.Frame(notebook)
        notebook.add(tasks_panel, text="补全计划")
        self.tasks = ttk.Treeview(tasks_panel, columns=("phase", "title", "count", "owner", "action"), show="headings")
        for key, label, width in (("phase", "阶段", 45), ("title", "待补功能", 170), ("count", "影响项", 65),
                                   ("owner", "所缺内容", 100), ("action", "实施要求", 480)):
            self.tasks.heading(key, text=label)
            self.tasks.column(key, width=width, anchor="center" if key in ("phase", "count") else "w", stretch=key == "action")
        task_scroll = ttk.Scrollbar(tasks_panel, command=self.tasks.yview)
        self.tasks.configure(yscrollcommand=task_scroll.set)
        task_scroll.pack(side="right", fill="y")
        task_horizontal = ttk.Scrollbar(tasks_panel, orient="horizontal", command=self.tasks.xview)
        self.tasks.configure(xscrollcommand=task_horizontal.set)
        task_horizontal.pack(side="bottom", fill="x")
        self.tasks.pack(fill="both", expand=True)
        ttk.Label(main, text="待迁移列表最多显示1000项；完整原记录见报告。日志不含脚本正文或变量值。", foreground="#7c8ba0").pack(anchor="w", pady=(8, 10))
        actions = ttk.Frame(main)
        actions.pack(fill="x")
        self.convert_button = ttk.Button(actions, text="开始转换", style="Accent.TButton", command=self.convert, state="disabled")
        self.convert_button.pack(side="left")
        self.cancel_button = ttk.Button(actions, text="取消", command=self.request_cancel, state="disabled")
        self.cancel_button.pack(side="left", padx=8)
        self.report_button = ttk.Button(actions, text="打开详细报告", command=self.open_report, state="disabled")
        self.report_button.pack(side="right")
        self.folder_button = ttk.Button(actions, text="打开输出目录", command=self.open_folder, state="disabled")
        self.folder_button.pack(side="right", padx=8)
        self.log_button = ttk.Button(actions, text="保存日志", command=self.save_log)
        self.log_button.pack(side="right", padx=8)
        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(100, self.pump)
        self.log("工具已就绪；请选择ZIP。自带编译检查器：" + ("可用" if bundled_validator() else "未打包，仅生成候选/草稿"))

    def log(self, message):
        line = datetime.datetime.now().strftime("%H:%M:%S") + "  " + message
        self.log_lines.append(line)
        self.log_view.configure(state="normal")
        self.log_view.delete("1.0", "end")
        self.log_view.insert("end", "\n".join(self.log_lines))
        self.log_view.see("end")
        self.log_view.configure(state="disabled")

    def choose_archive(self):
        name = filedialog.askopenfilename(filetypes=[("旧工程压缩包", "*.zip"), ("所有文件", "*.*")])
        if name:
            self.archive_path.set(name)
            self.analyze()

    def choose_output(self):
        name = filedialog.askdirectory()
        if name:
            self.output_parent.set(name)

    def send(self, kind, value):
        if kind == "progress":
            try:
                self.events.put_nowait((kind, value))
            except queue.Full:
                pass
        else:
            self.events.put((kind, value), timeout=5)

    def set_busy(self, busy):
        for widget in (self.choose_button, self.output_button, self.analyze_button, self.file_field,
                       self.output_field, self.log_button):
            widget.configure(state="disabled" if busy else "normal")
        self.entry_box.configure(state="disabled" if busy else "readonly")
        self.cancel_button.configure(state="normal" if busy else "disabled")
        self.convert_button.configure(state="normal" if not busy and self.fingerprint else "disabled")

    def run(self, operation):
        if self.worker and self.worker.is_alive():
            return
        self.cancel.clear()
        self.set_busy(True)
        self.progress["value"] = 0
        self.stage, self.percent = None, -1

        def work():
            try:
                self.send("result", operation())
            except Cancelled:
                self.send("cancelled", None)
            except Exception as error:
                self.send("error", failure_message(error) + " 原文件未修改。")
        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()

    def analyze(self):
        path = pathlib.Path(self.archive_path.get())
        if not path.is_file():
            messagebox.showwarning("选择工程", "请选择有效的旧工程ZIP。")
            return
        self.fingerprint = None
        self.entry.set("")
        self.log("开始分析压缩包、插件层级、变量和资源。")
        self.status.set("正在分析…")

        def operation():
            archive = LegacyArchive(path)
            try:
                names = sorted(name for name in archive.entries if name.endswith(".cj"))
                inspection = archive.inspect(Job(self.cancel.is_set))
                if self.cancel.is_set():
                    raise Cancelled()
                return {"mode": "analyze", "path": str(path), "names": names, "inspection": inspection,
                        "fingerprint": hashlib.sha256(archive.raw_bytes).hexdigest()}
            finally:
                archive.close()
        self.run(operation)

    def convert(self):
        if not self.entry.get():
            messagebox.showwarning("选择入口", "请选择实际的入口插件，工具不会猜测业务入口。")
            return
        if self.archive_path.get() != self.analyzed_path:
            messagebox.showwarning("重新分析", "工程路径已改变，请重新分析。")
            return
        path, entry = pathlib.Path(self.archive_path.get()), self.entry.get()
        parent = pathlib.Path(self.output_parent.get()).absolute()
        output = parent / ("converted-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
        fingerprint = self.fingerprint
        self.pending_output = output
        self.log("开始转换：保留原稿，未支持语义会明确阻断。")
        self.status.set("正在转换…")

        def operation():
            archive = LegacyArchive(path)
            try:
                if hashlib.sha256(archive.raw_bytes).hexdigest() != fingerprint:
                    raise ValueError("archive changed after analysis")
                job = Job(self.cancel.is_set, lambda stage, index, total: self.send("progress", (stage, index, total)))
                validator = bundled_validator()
                summary = Converter(archive, job).convert(output, entry, compiler_gate(validator) if validator else None)
                return {"mode": "convert", "output": str(output), "summary": summary}
            finally:
                archive.close()
        self.run(operation)

    def pump(self):
        while True:
            try:
                kind, value = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "progress":
                stage, index, total = value
                percent = int(index * 100 / total) if total else 0
                self.progress["value"] = percent
                self.status.set(STAGES.get(stage, stage) + f"：{index}/{total}")
                if stage != self.stage or percent // 25 != self.percent // 25:
                    self.log(STAGES.get(stage, stage) + f"  {index}/{total}")
                self.stage, self.percent = stage, percent
                continue
            self.set_busy(False)
            if kind == "result" and value["mode"] == "analyze":
                self.fingerprint, self.analyzed_path = value["fingerprint"], value["path"]
                self.entry_box["values"] = value["names"]
                info = value["inspection"]
                self.stats.set(f"插件 {info['plugins']}    变量 {info['variables']}    图片 {info['images']}")
                self.log(f"分析完成：{info['plugins']}个插件、{info['variables']}个变量、{info['images']}张图片。")
                self.status.set("分析完成。请选择入口插件，再开始转换。")
                self.convert_button.configure(state="normal")
                self.progress["value"] = 100
            elif kind == "result":
                self.last_output = pathlib.Path(value["output"])
                summary = value["summary"]
                active = summary.get("convertedActiveOperations", summary["convertedOperations"])
                disabled = summary.get("preservedDisabled", 0)
                self.stats.set(f"插件 {summary['plugins']}    变量 {summary['mappedVariables']}    图片 {summary['convertedImages']}    操作 {active}    保留禁用 {disabled}    待迁移 {summary['unsupportedItems']}")
                message = {"blocked": "基础转换完成，但还有未迁移项，当前不能运行。", "converted": "转换完成，编译检查通过。",
                           "candidate-needs-compiler-validation": "候选已生成，但尚未经过编译检查。"}[summary["status"]]
                self.status.set(message)
                self.log(message)
                self.log(f"已迁移{active}条活跃操作，保留{disabled}条禁用步骤；待处理{summary['unsupportedItems']}项。")
                self.progress["value"] = 100
                for widget in (self.report_button, self.folder_button):
                    widget.configure(state="normal")
                try:
                    report_path = self.last_output / "report.json"
                    if report_path.stat().st_size > 128 * 1024 * 1024:
                        raise ValueError("report exceeds view budget")
                    report = json.loads(report_path.read_text(encoding="utf-8"))
                    self.issue_records = report["blockers"]
                    self.plugin_aliases = report.get("pluginAliases", {})
                    self.remaining_tasks = report.get("remainingTasks", [])
                    self.refresh_issues()
                    self.tasks.delete(*self.tasks.get_children())
                    for item in self.remaining_tasks:
                        self.tasks.insert("", "end", values=(item["phase"], item["title"], item["affectedItems"], item["owner"], item["action"]))
                except (OSError, ValueError, KeyError, TypeError):
                    self.issue_records, self.plugin_aliases, self.remaining_tasks = [], {}, []
                    self.refresh_issues()
                    self.tasks.delete(*self.tasks.get_children())
                    self.log("报告表格读取失败；转换产物已保存，可手工查看报告。")
                try:
                    self.persist_log(self.last_output / "tool.log")
                except OSError:
                    self.log("转换产物已保存，但日志文件写入失败，可手工保存日志。")
            elif kind == "cancelled":
                self.status.set("已取消，未发布成功结果；原文件未修改。")
                self.log("用户取消任务；原文件未修改。")
            else:
                self.status.set(value)
                self.log(value)
            if self.closing:
                self.root.destroy()
                return
        self.root.after(100, self.pump)

    def request_cancel(self):
        self.cancel.set()
        self.log("正在取消，请等待当前检查点收尾。")
        self.cancel_button.configure(state="disabled")

    def refresh_issues(self):
        self.issues.delete(*self.issues.get_children())
        selected = self.issue_category.get()
        count = 0
        for index, issue in enumerate(self.issue_records):
            if selected != "全部" and CATEGORY_LABELS.get(issue.get("category"), "其他待核对") != selected:
                continue
            self.issues.insert("", "end", iid=str(index), values=(issue["source"], issue["line"], reason_label(issue["reason"])))
            count += 1
            if count >= 1000:
                break

    def show_issue(self, event=None):
        selection = self.issues.selection()
        if not selection or not self.last_output:
            return
        issue = self.issue_records[int(selection[0])]
        try:
            original, converted = inspect_issue(self.last_output, issue, self.plugin_aliases)
        except (OSError, ValueError, KeyError):
            messagebox.showerror("读取失败", "无法读取对照内容，请在详细报告中查看。")
            return
        window = tk.Toplevel(self.root)
        window.title("迁移对照 · 私有内容仅本地查看")
        window.geometry("1000x620")
        window.minsize(700, 430)
        heading = ttk.Frame(window, padding=12)
        heading.pack(fill="x")
        ttk.Label(heading, text=reason_label(issue["reason"]), wraplength=940).pack(anchor="w")
        columns = ttk.Panedwindow(window, orient="horizontal")
        columns.pack(fill="both", expand=True, padx=12, pady=(0, 12))
        for title, content in (("旧记录原字段", original), ("当前转换节点", converted)):
            panel = ttk.Frame(columns, padding=8)
            columns.add(panel, weight=1)
            ttk.Label(panel, text=title, foreground="#386bff").pack(anchor="w", pady=(0, 8))
            text = tk.Text(panel, wrap="word", font=("Consolas", 10), background="#f8faff", relief="flat")
            scroll = ttk.Scrollbar(panel, command=text.yview)
            scroll.pack(side="right", fill="y")
            text.configure(yscrollcommand=scroll.set)
            text.pack(fill="both", expand=True)
            text.insert("end", content)
            text.configure(state="disabled")

    def persist_log(self, path):
        path.write_text("\n".join(self.log_lines) + "\n", encoding="utf-8")

    def save_log(self):
        name = filedialog.asksaveasfilename(defaultextension=".log", initialfile="converter.log")
        if name:
            try:
                self.persist_log(pathlib.Path(name))
                self.log("日志已保存（不含脚本正文和变量值）。")
            except OSError:
                messagebox.showerror("保存失败", "无法写入日志文件。")

    def open_report(self):
        if self.last_output:
            self.open_path(self.last_output / "report.html")

    def open_folder(self):
        if self.last_output:
            self.open_path(self.last_output)

    def open_path(self, path):
        try:
            os.startfile(str(path))
        except OSError:
            messagebox.showerror("打开失败", "无法打开文件；可在输出目录手工查看。")

    def close(self):
        if self.worker and self.worker.is_alive():
            if messagebox.askyesno("取消任务", "正在处理工程，取消后关闭工具？"):
                self.closing = True
                self.request_cancel()
        else:
            self.root.destroy()


def main():
    root = tk.Tk()
    ConverterWindow(root)
    root.mainloop()
    return 0
