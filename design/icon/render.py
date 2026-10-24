from playwright.sync_api import sync_playwright
import pathlib, os
W = os.path.dirname(os.path.abspath(__file__))
src = pathlib.Path(os.path.join(W, "icons.html")).as_uri()
with sync_playwright() as p:
    b = p.chromium.launch(executable_path="/usr/local/bin/chromium",
                          args=["--no-sandbox","--disable-dev-shm-usage","--force-device-scale-factor=1"])
    pg = b.new_page(viewport={"width":1600,"height":1400}, device_scale_factor=1)
    pg.goto(src, wait_until="networkidle")
    pg.wait_for_timeout(700)
    raws = pg.query_selector_all(".raw")
    print("raw 图标数:", len(raws))
    for i, el in enumerate(raws, 1):
        el.screenshot(path=os.path.join(W, f"icon{i}.png"))
    pg.query_selector("#sheet").screenshot(path=os.path.join(W, "sheet.png"))
    b.close()
print("渲染完成")
