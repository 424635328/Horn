# 语雀渲染问题

行内公式写成了 \(x + y\)，语雀不会转换。

<details>
<summary>展开</summary>

折叠块里的 Markdown 在语雀不解析。

</details>

紧贴分割线
---
下一行

$$
a + b

```mermaid
flowchart TD
    subgraph 输入层：
        A[开始(第一步)] --> B["处理"]
    end
    B <-->|"双向"| A
```
