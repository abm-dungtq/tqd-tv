#!/usr/bin/env python3
"""
Trích xuất giao diện Web Remote từ WebRemoteServer.java sang Cloudflare Worker (worker.js)
đảm bảo 100% chuẩn UTF-8 tiếng Việt và Emoji (dùng json.loads / json.dumps).
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_FILE = os.path.join(
    ROOT,
    "launcher/app/src/main/java/com/gathin/tvhome/WebRemoteServer.java",
)
OUT_WORKER = os.path.join(ROOT, "cloudflare-worker/worker.js")


def main():
    with open(JAVA_FILE, "r", encoding="utf-8") as f:
        text = f.read()

    start = text.index("private static String buildHtmlPage() {")
    end = text.rindex("    }\n}")
    body = text[start:end]

    strings = re.findall(r'"(?:[^"\\]|\\.)*"', body)
    html = "".join(json.loads(s) for s in strings)

    # Kiểm định an toàn UTF-8 tiếng Việt trước khi xuất worker.js
    for token in ("Nguồn", "Chọn Ảnh Từ Điện Thoại", "Đổi Cảnh Kế Tiếp", "Hoạt hình"):
        assert token in html, f"Lỗi kiểm định UTF-8: không tìm thấy chuỗi '{token}'"

    worker = f"""export default {{
  async fetch(request) {{
    const html = {json.dumps(html, ensure_ascii=False)};
    return new Response(html, {{
      headers: {{
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "no-store, max-age=0"
      }}
    }});
  }}
}};
"""
    with open(OUT_WORKER, "w", encoding="utf-8") as f:
        f.write(worker)

    print(f"[OK] Đã sinh {OUT_WORKER} (HTML length = {len(html)} chars, UTF-8 verified)")


if __name__ == "__main__":
    main()
