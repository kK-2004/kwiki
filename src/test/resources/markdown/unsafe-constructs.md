# 危险内容必须被清洗

<script>alert('xss')</script>

<img src="https://ok.internal/pic.png" onerror="steal()" alt="logo">

[恶意链接](javascript:alert(1))

<a href="https://ok.internal" onclick="evil()">伪装链接</a>

正常内容保持不变：**这段话应当保留**。
