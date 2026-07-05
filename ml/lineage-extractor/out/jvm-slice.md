# 041-R JVM 扩语言 held-out 切片对比（加强实验 D）

同一 JVM 增强 held-out（n=600，其中 JVM 切片 scala+java = 126）。
before = run3（Python/Shell-only 训练，未见 JVM）；after = run-jvm（四语言训练）。

## 按 task_type 切片（JVM vs 既有语言）

| 模型 · 切片 | precision | recall | 方向准确率 | 幻觉率 |
| --- | --- | --- | --- | --- |
| run3(before) · JVM(scala+java) | 0.9877 | 0.9917 | 0.9917 | 0.0041 |
| run3(before) · existing(py+sh) | 0.9930 | 0.9930 | 0.9930 | 0.0023 |
| run3(before) · SCALA | 0.9779 | 0.9852 | 0.9852 | 0.0074 |
| run3(before) · JAVA | 1.0000 | 1.0000 | 1.0000 | 0.0000 |
| run3(before) · PYTHON | 0.9921 | 0.9921 | 0.9921 | 0.0000 |
| run3(before) · SHELL | 0.9943 | 0.9943 | 0.9943 | 0.0057 |
| | | | | |
| run-jvm(after) · JVM(scala+java) | 0.9959 | 0.9959 | 0.9959 | 0.0041 |
| run-jvm(after) · existing(py+sh) | 0.9883 | 0.9883 | 0.9883 | 0.0023 |
| run-jvm(after) · SCALA | 0.9926 | 0.9926 | 0.9926 | 0.0074 |
| run-jvm(after) · JAVA | 1.0000 | 1.0000 | 1.0000 | 0.0000 |
| run-jvm(after) · PYTHON | 0.9841 | 0.9841 | 0.9841 | 0.0000 |
| run-jvm(after) · SHELL | 0.9943 | 0.9943 | 0.9943 | 0.0057 |
| | | | | |

## 整体 + jvm form_family

| 模型 | 整体 prec | 整体 方向 | 整体 幻觉 | jvm-fam prec | jvm-fam 方向 | jvm-fam 幻觉 |
| --- | --- | --- | --- | --- | --- | --- |
| run3(before) | 0.9918 | 0.9927 | 0.0027 | 0.9877 | 0.9917 | 0.0041 |
| run-jvm(after) | 0.9899 | 0.9899 | 0.0027 | 0.9959 | 0.9959 | 0.0041 |
